package com.linechatbot.service;

import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排程推播 poller：每 30 秒檢查一次有沒有 SCHEDULED 任務到時可送出，
 * 到時就轉送給 {@link BroadcastService#submit(Long)} 走正常派發流程。
 *
 * <p>失敗的 submit 會記錄錯誤但不阻塞其他任務（每筆獨立 try/catch）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BroadcastScheduler {

    private final BroadcastTaskRepository taskRepository;
    private final BroadcastService broadcastService;

    @Scheduled(fixedDelayString = "${broadcast.scheduler.interval-ms:30000}")
    public void fireDueScheduledTasks() {
        List<BroadcastTask> due = taskRepository.findDueScheduled(LocalDateTime.now());
        if (due.isEmpty()) return;

        log.info("排程觸發 {} 個到時任務", due.size());
        for (BroadcastTask t : due) {
            try {
                broadcastService.submit(t.getId());
                log.info("排程任務已送出：id={}, name={}", t.getId(), t.getName());
            } catch (Exception e) {
                log.error("排程任務送出失敗：id={}", t.getId(), e);
            }
        }
    }
}
