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
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.MulticastRequest;
import com.linecorp.bot.messaging.model.PushMessageRequest;
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

        boolean isPush = "PUSH".equals(task.getApiMode());

        // 依模式取對應 token（最多等 30 秒）
        boolean acquired = isPush
                ? rateLimiter.acquirePush(30_000)
                : rateLimiter.acquireMulticast(30_000);
        if (!acquired) {
            log.warn("rate limit acquire timeout，重新排程 chunk：chunkId={}, mode={}",
                    chunkId, task.getApiMode());
            scheduleRetry(chunk, /*incrementAttempt*/ false, "rate-limit-timeout");
            return true;
        }

        if (isPush) {
            sendViaPush(chunk, task);
        } else {
            sendViaMulticast(chunk, task);
        }
        return true;
    }

    // ── 私有 ──────────────────────────────────────────────────

    /**
     * Multicast 模式：一次 API call 送整個 chunk（≤500 人），LINE 回 200 = 整批當成功。
     */
    private void sendViaMulticast(BroadcastChunk chunk, BroadcastTask task) {
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

            log.info("Chunk multicast 成功：chunkId={}, taskId={}, recipients={}, requestId={}",
                    chunk.getId(), task.getId(), recipients.size(), result.requestId());

        } catch (Exception e) {
            handleFailure(chunk, recipients.size(), task.getId(), e);
        }
    }

    /**
     * Push 模式：迭代 recipients，逐一 push。每人取自己的 retry key + per-user rate token。
     * <p>4xx（已退追、無效 ID 等）直接視為個別 user 失敗、不重試；其他 exception 走 chunk 重試流程。
     */
    private void sendViaPush(BroadcastChunk chunk, BroadcastTask task) {
        chunk.setStatus("SENDING");
        chunk.setAttempts(chunk.getAttempts() + 1);
        chunk.setLastAttemptAt(LocalDateTime.now());
        chunkRepository.save(chunk);

        List<Message> messages = parseMessages(task.getMessageContent());
        List<String> recipients = parseRecipientIds(chunk.getRecipientIds());

        int success = 0;
        int failed = 0;
        String lastRequestId = null;
        Exception lastFatal = null;

        for (int i = 0; i < recipients.size(); i++) {
            String userId = recipients.get(i);
            // 第一個 token 進入 chunk 時已取，後面每筆都要再取
            if (i > 0 && !rateLimiter.acquirePush(30_000)) {
                lastFatal = new IllegalStateException("push rate limit acquire timeout mid-chunk");
                break;
            }
            UUID perUserKey = derivePushRetryKey(chunk, userId);
            try {
                Result<?> result = messagingApiClient
                        .pushMessage(perUserKey, new PushMessageRequest(userId, messages, false, null))
                        .get();
                success++;
                lastRequestId = result.requestId();
            } catch (Exception e) {
                if (isClient4xx(e)) {
                    failed++;
                    log.debug("Push 4xx，標記個別 user failed：chunkId={}, userId={}, err={}",
                            chunk.getId(), maskUser(userId), e.getMessage());
                } else {
                    // 視為 chunk 級錯誤，留 lastFatal 走 handleFailure
                    lastFatal = e;
                    break;
                }
            }
        }

        // 若中途 fatal，把已成功的計上去再走 retry/fail 流程
        if (lastFatal != null) {
            // 先把已 push 成功的部分計數，避免重試造成重複（依賴 LINE retry key 去重，但計數要對）
            if (success > 0) {
                counterService.recordChunkResult(task.getId(), success, success, 0);
                // 注意：不執行 finalize，等 chunk 真的終結再判
            }
            handleFailure(chunk, recipients.size() - success - failed, task.getId(), lastFatal);
            return;
        }

        // 全跑完（沒 fatal）
        chunk.setStatus(failed == 0 ? "SUCCESS" : "PARTIAL");
        chunk.setLineRequestId(lastRequestId);
        chunk.setSentAt(LocalDateTime.now());
        chunk.setErrorCode(failed > 0 ? "PARTIAL_FAILURES" : null);
        chunk.setErrorMessage(failed > 0 ? failed + " 個 user push 失敗（4xx）" : null);
        chunk.setNextRetryAt(null);
        chunkRepository.save(chunk);

        boolean isLast = counterService.recordChunkResult(
                task.getId(), recipients.size(), success, failed);
        if (isLast) counterService.finalizeTask(task.getId());

        log.info("Chunk push 完成：chunkId={}, taskId={}, success={}, failed={}",
                chunk.getId(), task.getId(), success, failed);
    }

    /**
     * 判定 exception 是否為 LINE 回 4xx 的「永久性錯誤」（不重試）。
     * <p>LINE SDK 把 HTTP error 包成 {@link AbstractLineClientException}，內含 {@code getCode()}，
     * 4xx 視為使用者端錯誤（已退追、無效 ID、訊息格式錯等），直接標記該 user failed 不重試；
     * 其他 exception 視為 transient（網路抖動、5xx 等），走 chunk 重試流程。
     *
     * <p>不細分 408/429：在 push 模式下，這兩種 4xx 若立刻重試也只會再觸發；
     * 真正大量 429 應該由 {@link com.linechatbot.service.ratelimit.LineApiRateLimiter} 預防。
     */
    private boolean isClient4xx(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof AbstractLineClientException lex) {
                int code = lex.getCode();
                if (code >= 400 && code < 500) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private String maskUser(String userId) {
        if (userId == null || userId.length() < 8) return userId;
        return userId.substring(0, 4) + "***" + userId.substring(userId.length() - 4);
    }

    private UUID derivePushRetryKey(BroadcastChunk chunk, String userId) {
        String seed = "broadcast-" + chunk.getId() + "-" + chunk.getAttempts() + "-" + userId;
        return UUID.nameUUIDFromBytes(seed.getBytes());
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
