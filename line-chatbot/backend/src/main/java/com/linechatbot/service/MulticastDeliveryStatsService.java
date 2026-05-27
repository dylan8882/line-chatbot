package com.linechatbot.service;

import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.model.entity.MulticastDailyDelivery;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.MulticastDailyDeliveryRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.NumberOfMessagesResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Multicast 任務的「LINE 平台日送達增量」結算服務。
 *
 * <p>背景：multicast 發送後 LINE 不會回 per-user 結果，只能透過
 * {@code GET /v2/bot/message/delivery/multicast?date=YYYYMMDD} 拿到「當日累計送達數」。
 * 把這個累計拆分成 per-task 的增量，需要：
 *
 * <ol>
 *   <li>記錄上次查詢的 total（{@code multicast_daily_delivery.last_total}）</li>
 *   <li>本次任務的 delta = LINE 回的 total − last_total</li>
 *   <li>把 last_total 更新成新 total，給同日下一個任務參考</li>
 * </ol>
 *
 * <p>限制：LINE API 約 5–10 分鐘延遲，所以 task 完成 5 分鐘內不會結算；
 * 又因為當日 total 涵蓋所有 multicast 任務（包含本系統外的呼叫），
 * delta 是「估算值」而非精準歸因，UI 需要在 tooltip 說明清楚。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MulticastDeliveryStatsService {

    /** LINE delivery API 預期需要的延遲 buffer */
    private static final Duration DELIVERY_LAG = Duration.ofMinutes(5);

    private static final DateTimeFormatter LINE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BroadcastTaskRepository taskRepository;
    private final MulticastDailyDeliveryRepository dailyRepository;
    private final MessagingApiClient messagingApiClient;

    /**
     * 排程入口：每 5 分鐘掃一次。
     * 用 fixedDelay 避免上一輪還沒跑完就重疊（LINE API 偶爾會慢）。
     */
    @Scheduled(fixedDelayString = "${broadcast.multicast-delivery.poll-ms:300000}")
    public void reconcile() {
        LocalDateTime before = LocalDateTime.now().minus(DELIVERY_LAG);
        List<BroadcastTask> pending = taskRepository.findMulticastPendingDeliveryStats(before);
        if (pending.isEmpty()) return;

        log.debug("Multicast delivery 結算：{} 個 task 待處理", pending.size());
        for (BroadcastTask task : pending) {
            try {
                settleOne(task.getId());
            } catch (Exception e) {
                log.warn("Multicast delivery 結算失敗：taskId={}, error={}",
                        task.getId(), e.getMessage());
            }
        }
    }

    /**
     * 結算單一任務：撈 LINE 當日 total → 算 delta → 寫回 task + 更新 last_total。
     * 獨立 transaction，避免單一任務失敗影響其他任務。
     */
    @Transactional
    public void settleOne(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        // race condition 防禦：可能上一輪同時跑、已寫入
        if (task.getLineDeliveredDelta() != null) return;
        if (task.getFinishedAt() == null) return;

        LocalDate date = task.getFinishedAt().toLocalDate();
        String lineDate = date.format(LINE_DATE_FMT);

        NumberOfMessagesResponse resp;
        try {
            Result<NumberOfMessagesResponse> result = messagingApiClient
                    .getNumberOfSentMulticastMessages(lineDate)
                    .get();
            resp = result.body();
        } catch (Exception e) {
            log.warn("查詢 LINE multicast delivery 失敗：taskId={}, date={}, error={}",
                    taskId, lineDate, e.getMessage());
            return;
        }

        if (resp == null || resp.status() != NumberOfMessagesResponse.Status.READY) {
            log.debug("LINE delivery 尚未 ready：taskId={}, date={}, status={}",
                    taskId, lineDate, resp == null ? "null" : resp.status());
            return;
        }

        long total = resp.success() == null ? 0L : resp.success();
        MulticastDailyDelivery daily = dailyRepository.findById(date)
                .orElseGet(() -> MulticastDailyDelivery.builder()
                        .date(date)
                        .lastTotal(0L)
                        .build());

        long delta = Math.max(0L, total - daily.getLastTotal());
        task.setLineDeliveredDelta(delta);
        taskRepository.save(task);

        daily.setLastTotal(total);
        dailyRepository.save(daily);

        log.info("Multicast delivery 結算完成：taskId={}, date={}, total={}, delta={}",
                taskId, lineDate, total, delta);
    }
}
