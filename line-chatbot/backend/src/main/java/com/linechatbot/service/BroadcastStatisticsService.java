package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.BroadcastFailureDTO;
import com.linechatbot.model.dto.BroadcastStatisticsDTO;
import com.linechatbot.model.dto.ClickStatisticsDTO;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.model.entity.ClickLink;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.ClickEventRepository;
import com.linechatbot.repository.ClickLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推播成效統計查詢：基於 broadcast_chunks 表計算成功率、錯誤分布、發送速率等指標。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastStatisticsService {

    private final BroadcastTaskRepository taskRepository;
    private final BroadcastChunkRepository chunkRepository;
    private final ClickLinkRepository clickLinkRepository;
    private final ClickEventRepository clickEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 任務成效摘要。
     */
    public BroadcastStatisticsDTO getStatistics(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));
        List<BroadcastChunk> chunks = chunkRepository.findByTaskIdOrderByChunkIndex(taskId);

        int successChunks = 0, failedChunks = 0, retryingChunks = 0, pendingChunks = 0;
        int delivered = 0;
        long attemptsSum = 0;
        Map<String, Integer> errorBreakdown = new HashMap<>();

        for (BroadcastChunk c : chunks) {
            attemptsSum += c.getAttempts() == null ? 0 : c.getAttempts();
            switch (c.getStatus()) {
                case "SUCCESS" -> {
                    successChunks++;
                    delivered += parseRecipientCount(c);
                }
                case "FAILED" -> {
                    failedChunks++;
                    String code = c.getErrorCode() != null ? c.getErrorCode() : "UNKNOWN";
                    errorBreakdown.merge(code, 1, Integer::sum);
                }
                case "RETRYING", "SENDING" -> retryingChunks++;
                case "PENDING" -> pendingChunks++;
                default -> { /* CANCELLED 等不計入 */ }
            }
        }

        int totalChunks = chunks.size();
        double successRate = totalChunks == 0 ? 0
                : (successChunks * 1.0) / Math.max(1, successChunks + failedChunks);
        double avgAttempts = totalChunks == 0 ? 0 : (attemptsSum * 1.0) / totalChunks;

        Long durationMs = null;
        double sendRatePerSec = 0;
        LocalDateTime started = task.getStartedAt();
        LocalDateTime endRef = task.getFinishedAt() != null ? task.getFinishedAt() : LocalDateTime.now();
        if (started != null) {
            durationMs = Duration.between(started, endRef).toMillis();
            if (durationMs > 0) {
                sendRatePerSec = (delivered * 1000.0) / durationMs;
            }
        }

        List<BroadcastStatisticsDTO.ErrorBucket> buckets = errorBreakdown.entrySet().stream()
                .map(e -> BroadcastStatisticsDTO.ErrorBucket.builder()
                        .errorCode(e.getKey())
                        .count(e.getValue())
                        .build())
                .toList();

        return BroadcastStatisticsDTO.builder()
                .taskId(taskId)
                .status(task.getStatus())
                .totalRecipients(task.getTotalRecipients() == null ? 0 : task.getTotalRecipients())
                .totalChunks(totalChunks)
                .successChunks(successChunks)
                .failedChunks(failedChunks)
                .retryingChunks(retryingChunks)
                .pendingChunks(pendingChunks)
                .deliveredRecipients(delivered)
                .successRate(successRate)
                .avgAttempts(avgAttempts)
                .durationMs(task.getFinishedAt() != null ? durationMs : null)
                .sendRatePerSecond(sendRatePerSec)
                .errorBreakdown(buckets)
                .build();
    }

    /**
     * 任務的失敗 / 重試中 chunk 清單（用於失敗清單頁）。
     */
    public List<BroadcastFailureDTO> getFailures(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new ResourceNotFoundException("BroadcastTask", taskId);
        }
        List<BroadcastChunk> chunks = chunkRepository
                .findByTaskIdAndStatusInOrderByChunkIndex(taskId, List.of("FAILED", "RETRYING"));
        return chunks.stream().map(this::toFailureDTO).toList();
    }

    /**
     * Phase 7：點擊統計。
     *
     * <p>CTR = totalClicks / deliveredRecipients（送達人數）。
     * deliveredRecipients 取自 task.successCount（Phase 3 Counter 累計）。
     */
    public ClickStatisticsDTO getClickStatistics(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));

        List<ClickLink> links = clickLinkRepository.findByTaskIdOrderByLinkIndex(taskId);
        long total = clickEventRepository.countByTaskId(taskId);
        long uniqueIps = clickEventRepository.countDistinctIpByTaskId(taskId);
        int delivered = task.getSuccessCount() == null ? 0 : task.getSuccessCount();
        double ctr = delivered == 0 ? 0 : (total * 1.0) / delivered;

        List<ClickStatisticsDTO.LinkStat> linkStats = links.stream()
                .map(l -> ClickStatisticsDTO.LinkStat.builder()
                        .linkId(l.getId())
                        .linkIndex(l.getLinkIndex())
                        .targetUrl(l.getTargetUrl())
                        .clickCount(l.getClickCount())
                        .build())
                .toList();

        return ClickStatisticsDTO.builder()
                .taskId(taskId)
                .totalClicks(total)
                .uniqueIps(uniqueIps)
                .deliveredRecipients(delivered)
                .ctr(ctr)
                .links(linkStats)
                .build();
    }

    // ── 內部 ──────────────────────────────────────────────────

    private int parseRecipientCount(BroadcastChunk c) {
        try {
            List<String> ids = objectMapper.readValue(c.getRecipientIds(), new TypeReference<>() {});
            return ids.size();
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    private BroadcastFailureDTO toFailureDTO(BroadcastChunk c) {
        BroadcastFailureDTO dto = new BroadcastFailureDTO();
        dto.setChunkId(c.getId());
        dto.setChunkIndex(c.getChunkIndex());
        dto.setRecipientCount(parseRecipientCount(c));
        dto.setAttempts(c.getAttempts());
        dto.setStatus(c.getStatus());
        dto.setErrorCode(c.getErrorCode());
        dto.setErrorMessage(c.getErrorMessage());
        dto.setLastAttemptAt(c.getLastAttemptAt());
        dto.setNextRetryAt(c.getNextRetryAt());
        dto.setLineRequestId(c.getLineRequestId());
        return dto;
    }
}
