package com.linechatbot.service;

import com.linechatbot.model.dto.BroadcastProgressEvent;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.NarrowcastProgressResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Narrowcast 進度追蹤器：定期呼叫 LINE getNarrowcastProgress 更新仍在執行中的任務狀態，
 * 並廣播進度事件給 SSE 訂閱者。
 *
 * <p>NarrowcastProgressResponse.Phase 對應到我們的 task status：
 * <ul>
 *   <li>waiting / sending → RUNNING</li>
 *   <li>succeeded → COMPLETED</li>
 *   <li>failed → FAILED</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BroadcastNarrowcastPoller {

    private final BroadcastTaskRepository taskRepository;
    private final MessagingApiClient messagingApiClient;
    private final BroadcastProgressService progressService;

    @Scheduled(fixedDelayString = "${broadcast.narrowcast.poll-interval-ms:15000}")
    public void poll() {
        List<BroadcastTask> running = taskRepository
                .findByTargetTypeAndStatus("NARROWCAST", "RUNNING");
        if (running.isEmpty()) return;

        log.debug("Narrowcast poll：{} 個執行中任務", running.size());
        for (BroadcastTask task : running) {
            if (task.getNarrowcastRequestId() == null) continue;
            try {
                updateOne(task);
            } catch (Exception e) {
                log.warn("查詢 Narrowcast 進度失敗：taskId={}, requestId={}, error={}",
                        task.getId(), task.getNarrowcastRequestId(), e.getMessage());
            }
        }
    }

    private void updateOne(BroadcastTask task) throws Exception {
        Result<NarrowcastProgressResponse> result = messagingApiClient
                .getNarrowcastProgress(task.getNarrowcastRequestId())
                .get();
        NarrowcastProgressResponse body = result.body();
        if (body == null) return;

        Long target = body.targetCount();
        Long success = body.successCount();
        Long failed = body.failureCount();
        if (target != null) task.setTotalRecipients(target.intValue());
        if (success != null) task.setSuccessCount(success.intValue());
        if (failed != null) task.setFailedCount(failed.intValue());
        task.setSentCount((success != null ? success.intValue() : 0) + (failed != null ? failed.intValue() : 0));

        String eventType = "PROGRESS";
        NarrowcastProgressResponse.Phase phase = body.phase();
        if (phase != null) {
            switch (phase) {
                case SUCCEEDED -> {
                    task.setStatus("COMPLETED");
                    task.setFinishedAt(LocalDateTime.now());
                    eventType = "COMPLETED";
                }
                case FAILED -> {
                    task.setStatus("FAILED");
                    task.setErrorMessage(body.failedDescription());
                    task.setFinishedAt(LocalDateTime.now());
                    eventType = "FAILED";
                }
                default -> {
                    // waiting / sending → 維持 RUNNING
                }
            }
        }
        taskRepository.save(task);

        progressService.publish(BroadcastProgressEvent.builder()
                .type(eventType)
                .taskId(task.getId())
                .status(task.getStatus())
                .sentCount(task.getSentCount())
                .successCount(task.getSuccessCount())
                .failedCount(task.getFailedCount())
                .totalRecipients(task.getTotalRecipients())
                .build());
    }
}
