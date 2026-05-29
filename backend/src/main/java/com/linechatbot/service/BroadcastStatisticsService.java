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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     *
     * <p>PUSH 與 MULTICAST 的計算方式不同：
     * <ul>
     *   <li>PUSH：deliveredRecipients / successRate / sendRatePerSecond 用 task 的
     *       per-user 計數（task.successCount / failedCount，由 BroadcastCounterService 累計），
     *       因為 PUSH chunk 可能是 PARTIAL（部分使用者 4xx），不能拿整 chunk 當成功</li>
     *   <li>MULTICAST：用 chunk-level 計算（每個 SUCCESS chunk 算 N 人送達），
     *       因為 LINE 不會回 per-user 結果</li>
     * </ul>
     */
    public BroadcastStatisticsDTO getStatistics(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));
        List<BroadcastChunk> chunks = chunkRepository.findByTaskIdOrderByChunkIndex(taskId);

        int successChunks = 0, failedChunks = 0, retryingChunks = 0, pendingChunks = 0;
        int chunkDelivered = 0;
        long attemptsSum = 0;
        Map<String, Integer> errorBreakdown = new HashMap<>();

        for (BroadcastChunk c : chunks) {
            attemptsSum += c.getAttempts() == null ? 0 : c.getAttempts();
            switch (c.getStatus()) {
                case "SUCCESS" -> {
                    successChunks++;
                    chunkDelivered += parseRecipientCount(c);
                }
                case "PARTIAL" -> {
                    // PUSH 模式部分使用者 4xx；chunk 整體當完成，不重試
                    successChunks++;
                    String code = c.getErrorCode() != null ? c.getErrorCode() : "PARTIAL_FAILURES";
                    // errorBreakdown 以「失敗人數」為單位（不是 chunk 數）
                    errorBreakdown.merge(code, parseFailedUserCount(c), Integer::sum);
                }
                case "FAILED" -> {
                    failedChunks++;
                    String code = c.getErrorCode() != null ? c.getErrorCode() : "UNKNOWN";
                    // 整個 chunk 失敗 = chunk 內所有收件人都算失敗
                    errorBreakdown.merge(code, parseRecipientCount(c), Integer::sum);
                }
                case "RETRYING", "SENDING" -> retryingChunks++;
                case "PENDING" -> pendingChunks++;
                default -> { /* CANCELLED 等不計入 */ }
            }
        }

        int totalChunks = chunks.size();
        boolean isPush = "PUSH".equals(task.getApiMode());
        int taskSuccess = task.getSuccessCount() == null ? 0 : task.getSuccessCount();
        int taskFailed = task.getFailedCount() == null ? 0 : task.getFailedCount();

        int delivered;
        double successRate;
        if (isPush) {
            // Push 模式：用 task 的 per-user 累計
            delivered = taskSuccess;
            int denom = taskSuccess + taskFailed;
            successRate = denom == 0 ? 0 : (taskSuccess * 1.0) / denom;
        } else {
            // Multicast / Narrowcast：用 chunk-level 計算
            delivered = chunkDelivered;
            int denom = successChunks + failedChunks;
            successRate = denom == 0 ? 0 : (successChunks * 1.0) / denom;
        }
        double avgAttempts = totalChunks == 0 ? 0 : (attemptsSum * 1.0) / totalChunks;

        Long durationMs = null;
        double sendRatePerSec = 0;
        LocalDateTime started = task.getStartedAt();
        LocalDateTime endRef = task.getFinishedAt() != null ? task.getFinishedAt() : LocalDateTime.now();
        if (started != null) {
            durationMs = Duration.between(started, endRef).toMillis();
            if (durationMs > 0) {
                // Push 用「已處理人數」當分子（成功 + 失敗），multicast 用 delivered chunk 加總
                int rateNumerator = isPush ? (taskSuccess + taskFailed) : delivered;
                sendRatePerSec = (rateNumerator * 1000.0) / durationMs;
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

    /**
     * 從 PARTIAL chunk 的 error_message 抽出失敗人數。
     * BroadcastChunkProcessor 寫入格式為 "{N} 個 user push 失敗（4xx）"。
     * 抽不到時回 0（保守、不誇大錯誤數）。
     */
    private static final Pattern PARTIAL_FAILED_COUNT = Pattern.compile("^(\\d+)");

    private int parseFailedUserCount(BroadcastChunk c) {
        String msg = c.getErrorMessage();
        if (msg == null) return 0;
        Matcher m = PARTIAL_FAILED_COUNT.matcher(msg);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
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
