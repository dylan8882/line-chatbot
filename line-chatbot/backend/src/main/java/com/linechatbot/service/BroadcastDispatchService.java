package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.MulticastRequest;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 推播派發：呼叫 LINE multicast 將訊息送出，更新 chunk 狀態與 task 計數。
 *
 * <p>Phase 2 為同步逐 chunk 處理（單一執行緒內部循環），不含 Redis Queue 與 Worker Pool。
 * Phase 3 會改成 Redis Stream + Token Bucket 限速。
 *
 * <p><b>注意：</b>本 service 的所有 DB 寫入都用 explicit save，不依賴 dirty-checking。
 * 因為 @Async 方法是新執行緒，且本類內部的 self-call 不會經 Spring proxy，
 * 因此 @Transactional 在自呼叫情境下無效。改用 explicit save 確保每次變更都被 flush。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastDispatchService {

    private final BroadcastTaskRepository taskRepository;
    private final BroadcastChunkRepository chunkRepository;
    private final MessagingApiClient messagingApiClient;
    private final ObjectMapper objectMapper;

    /**
     * 非同步派發整個任務的所有 chunks。
     */
    @Async("broadcastWorkerExecutor")
    public void dispatchAsync(Long taskId) {
        log.info("開始派發推播任務：taskId={}", taskId);
        try {
            String messageContent = loadTask(taskId).getMessageContent();
            List<Message> messages = parseMessages(messageContent);
            List<BroadcastChunk> chunks = chunkRepository.findByTaskIdOrderByChunkIndex(taskId);

            for (BroadcastChunk chunk : chunks) {
                // 每次重新讀 task 狀態，支援中途取消
                BroadcastTask task = loadTask(taskId);
                if ("CANCELLED".equals(task.getStatus())) {
                    log.info("任務已取消，停止派發：taskId={}", taskId);
                    break;
                }

                if (!"PENDING".equals(chunk.getStatus())) continue;
                sendChunk(chunk, messages);
            }

            finalizeTask(taskId);
        } catch (Exception e) {
            log.error("派發任務時發生例外：taskId={}", taskId, e);
            markTaskFailed(taskId, e.getMessage());
        }
    }

    /**
     * 測試發送：對單一 lineUserId 用 pushMessage（不影響任務統計）。
     */
    public String testSend(String messageContent, String lineUserId) {
        List<Message> messages = parseMessages(messageContent);
        UUID retryKey = UUID.randomUUID();
        try {
            Result<?> result = messagingApiClient
                    .pushMessage(retryKey, new PushMessageRequest(lineUserId, messages, false, null))
                    .get();
            return result.requestId();
        } catch (Exception e) {
            log.error("測試推播失敗：userId={}", lineUserId, e);
            throw new IllegalArgumentException("測試推播失敗：" + e.getMessage());
        }
    }

    // ── 私有 ──────────────────────────────────────────────────

    private void sendChunk(BroadcastChunk chunk, List<Message> messages) {
        chunk.setStatus("SENDING");
        chunk.setAttempts(chunk.getAttempts() + 1);
        chunkRepository.save(chunk);

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
            chunkRepository.save(chunk);

            incrementTask(chunk.getTaskId(), recipients.size(), recipients.size(), 0);
            log.info("Chunk 發送成功：chunkId={}, taskId={}, recipients={}, requestId={}",
                    chunk.getId(), chunk.getTaskId(), recipients.size(), result.requestId());

        } catch (Exception e) {
            chunk.setStatus("FAILED");
            chunk.setErrorCode(e.getClass().getSimpleName());
            chunk.setErrorMessage(truncate(e.getMessage(), 1000));
            chunkRepository.save(chunk);

            incrementTask(chunk.getTaskId(), recipients.size(), 0, recipients.size());
            log.error("Chunk 發送失敗：chunkId={}, taskId={}, recipients={}",
                    chunk.getId(), chunk.getTaskId(), recipients.size(), e);
        }
    }

    private void incrementTask(Long taskId, int sentDelta, int successDelta, int failedDelta) {
        BroadcastTask task = loadTask(taskId);
        task.setSentCount(task.getSentCount() + sentDelta);
        task.setSuccessCount(task.getSuccessCount() + successDelta);
        task.setFailedCount(task.getFailedCount() + failedDelta);
        taskRepository.save(task);
    }

    private void finalizeTask(Long taskId) {
        BroadcastTask task = loadTask(taskId);
        if ("CANCELLED".equals(task.getStatus())) return;

        long failed = chunkRepository.countByTaskIdAndStatus(taskId, "FAILED");
        long success = chunkRepository.countByTaskIdAndStatus(taskId, "SUCCESS");
        long pending = chunkRepository.countByTaskIdAndStatusIn(taskId, List.of("PENDING", "SENDING", "RETRYING"));

        if (pending > 0) {
            log.warn("派發循環結束但仍有 pending chunk：taskId={}, pending={}", taskId, pending);
            return;
        }

        if (failed == 0) {
            task.setStatus("COMPLETED");
        } else if (success == 0) {
            task.setStatus("FAILED");
            task.setErrorMessage("所有分片均失敗");
        } else {
            task.setStatus("COMPLETED");
            task.setErrorMessage("部分分片失敗（success=" + success + ", failed=" + failed + "）");
        }
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
        log.info("任務派發結束：taskId={}, status={}, success={}, failed={}",
                taskId, task.getStatus(), success, failed);
    }

    private void markTaskFailed(Long taskId, String reason) {
        try {
            BroadcastTask task = loadTask(taskId);
            task.setStatus("FAILED");
            task.setErrorMessage(truncate(reason, 1000));
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
        } catch (Exception ignored) {
            // 已 log 過原始例外
        }
    }

    private BroadcastTask loadTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task 不存在：" + taskId));
    }

    private List<Message> parseMessages(String messageContent) {
        try {
            return objectMapper.readValue(messageContent, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("messages JSON 解析失敗：" + e.getOriginalMessage());
        }
    }

    private List<String> parseRecipientIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("recipientIds 解析失敗：" + e.getOriginalMessage());
        }
    }

    /**
     * 用 chunkId 與 attempts 衍生穩定 retry key，
     * LINE 收到相同 X-Line-Retry-Key 時不會重複扣訊息配額。
     */
    private UUID deriveRetryKey(BroadcastChunk chunk) {
        String seed = "broadcast-" + chunk.getId() + "-" + chunk.getAttempts();
        return UUID.nameUUIDFromBytes(seed.getBytes());
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
