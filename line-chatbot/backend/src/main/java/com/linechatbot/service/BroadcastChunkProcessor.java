package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.service.ratelimit.LineApiRateLimiter;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.MulticastRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 推播分片處理器：worker 從 stream 拿到 chunkId 後委派此 service 執行。
 *
 * <p>職責：限速 → 發送 → 計數 → 失敗時排程重試 / 標記終止。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastChunkProcessor {

    /** chunk 重試間隔（毫秒），依嘗試次數遞增 */
    private static final long[] BACKOFF_MS = { 1_000L, 5_000L, 30_000L, 120_000L };

    private final BroadcastChunkRepository chunkRepository;
    private final BroadcastTaskRepository taskRepository;
    private final MessagingApiClient messagingApiClient;
    private final LineApiRateLimiter rateLimiter;
    private final BroadcastQueueService queueService;
    private final BroadcastCounterService counterService;
    private final ObjectMapper objectMapper;

    /**
     * 處理單一 chunk。本方法被 worker 直接呼叫。
     *
     * @return true 若處理完成（無論成功失敗都已決定），caller 可 ACK；
     *         false 表示應跳過（task cancelled 等），caller 仍應 ACK 但不更新計數
     */
    public boolean process(Long chunkId) {
        Optional<BroadcastChunk> chunkOpt = chunkRepository.findById(chunkId);
        if (chunkOpt.isEmpty()) {
            log.warn("Chunk 不存在，跳過：chunkId={}", chunkId);
            return false;
        }
        BroadcastChunk chunk = chunkOpt.get();

        // 終態的 chunk 不重複處理（避免重複 ACK / 重複計數）
        if ("SUCCESS".equals(chunk.getStatus()) || "CANCELLED".equals(chunk.getStatus())) {
            return false;
        }

        BroadcastTask task = taskRepository.findById(chunk.getTaskId()).orElse(null);
        if (task == null) return false;
        if ("CANCELLED".equals(task.getStatus())) {
            chunk.setStatus("CANCELLED");
            chunkRepository.save(chunk);
            return false;
        }

        // 取得 multicast token（最多等 30 秒）
        if (!rateLimiter.acquireMulticast(30_000)) {
            log.warn("rate limit acquire timeout，重新排程 chunk：chunkId={}", chunkId);
            scheduleRetry(chunk, /*incrementAttempt*/ false, "rate-limit-timeout");
            return true;
        }

        send(chunk, task);
        return true;
    }

    // ── 私有 ──────────────────────────────────────────────────

    private void send(BroadcastChunk chunk, BroadcastTask task) {
        chunk.setStatus("SENDING");
        chunk.setAttempts(chunk.getAttempts() + 1);
        chunk.setLastAttemptAt(LocalDateTime.now());
        chunkRepository.save(chunk);

        List<Message> messages = parseMessages(task.getMessageContent());
        List<String> recipients = parseRecipientIds(chunk.getRecipientIds());
        UUID retryKey = deriveRetryKey(chunk);

        try {
            Result<?> result = messagingApiClient
                    .multicast(retryKey, new MulticastRequest(messages, recipients, false, null))
                    .get();

            chunk.setStatus("SUCCESS");
            chunk.setLineRequestId(result.requestId());
            chunk.setSentAt(LocalDateTime.now());
            chunk.setErrorCode(null);
            chunk.setErrorMessage(null);
            chunk.setNextRetryAt(null);
            chunkRepository.save(chunk);

            boolean isLast = counterService.recordChunkResult(
                    task.getId(), recipients.size(), recipients.size(), 0);
            if (isLast) counterService.finalizeTask(task.getId());

            log.info("Chunk 發送成功：chunkId={}, taskId={}, recipients={}, requestId={}",
                    chunk.getId(), task.getId(), recipients.size(), result.requestId());

        } catch (Exception e) {
            handleFailure(chunk, recipients.size(), task.getId(), e);
        }
    }

    private void handleFailure(BroadcastChunk chunk, int recipientCount, Long taskId, Exception e) {
        String errClass = e.getClass().getSimpleName();
        String errMsg = truncate(e.getMessage(), 1000);
        chunk.setErrorCode(errClass);
        chunk.setErrorMessage(errMsg);

        if (chunk.getAttempts() >= chunk.getMaxAttempts()) {
            chunk.setStatus("FAILED");
            chunk.setNextRetryAt(null);
            chunkRepository.save(chunk);

            boolean isLast = counterService.recordChunkResult(taskId, recipientCount, 0, recipientCount);
            if (isLast) counterService.finalizeTask(taskId);

            log.error("Chunk 重試上限，標記 FAILED：chunkId={}, attempts={}",
                    chunk.getId(), chunk.getAttempts(), e);
        } else {
            scheduleRetry(chunk, /*incrementAttempt*/ false, errMsg);
            log.warn("Chunk 失敗安排重試：chunkId={}, attempts={}, nextRetryAt={}, error={}",
                    chunk.getId(), chunk.getAttempts(), chunk.getNextRetryAt(), errMsg);
        }
    }

    private void scheduleRetry(BroadcastChunk chunk, boolean incrementAttempt, String reason) {
        if (incrementAttempt) {
            chunk.setAttempts(chunk.getAttempts() + 1);
        }
        int idx = Math.min(chunk.getAttempts() - 1, BACKOFF_MS.length - 1);
        long delay = BACKOFF_MS[Math.max(idx, 0)];
        long nextRetryAtMs = System.currentTimeMillis() + delay;

        chunk.setStatus("RETRYING");
        chunk.setNextRetryAt(LocalDateTime.now().plusNanos(delay * 1_000_000L));
        chunkRepository.save(chunk);

        queueService.scheduleRetry(chunk.getId(), nextRetryAtMs);
        log.debug("已安排 chunk 重試：chunkId={}, delayMs={}, reason={}", chunk.getId(), delay, reason);
    }

    private List<Message> parseMessages(String messageContent) {
        try {
            return objectMapper.readValue(messageContent, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("messages JSON 解析失敗：" + e.getOriginalMessage());
        }
    }

    private List<String> parseRecipientIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("recipientIds 解析失敗：" + e.getOriginalMessage());
        }
    }

    private UUID deriveRetryKey(BroadcastChunk chunk) {
        String seed = "broadcast-" + chunk.getId() + "-" + chunk.getAttempts();
        return UUID.nameUUIDFromBytes(seed.getBytes());
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
