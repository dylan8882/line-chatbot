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
        reconcileNow();
    }

    /**
     * 手動觸發版本：回傳結算結果摘要（給 admin 端點呼叫）。
     */
    public ReconcileSummary reconcileNow() {
        LocalDateTime before = LocalDateTime.now().minus(DELIVERY_LAG);
        List<BroadcastTask> pending = taskRepository.findMulticastPendingDeliveryStats(before);
        if (pending.isEmpty()) {
            log.info("Multicast delivery 結算：無待處理任務（含已完成 ≥ {} 分鐘的 multicast）",
                    DELIVERY_LAG.toMinutes());
            return new ReconcileSummary(0, 0, 0, 0);
        }

        log.info("Multicast delivery 結算：掃到 {} 個待處理任務", pending.size());
        int settled = 0;
        int notReady = 0;
        int errors = 0;
        for (BroadcastTask task : pending) {
            try {
                SettleResult r = settleOne(task.getId());
                switch (r) {
                    case SETTLED -> settled++;
                    case NOT_READY -> notReady++;
                    case ALREADY_SETTLED, SKIPPED -> { /* no-op for summary */ }
                }
            } catch (Exception e) {
                errors++;
                log.warn("Multicast delivery 結算失敗：taskId={}, error={}",
                        task.getId(), e.getMessage());
            }
        }
        log.info("Multicast delivery 結算結束：scanned={}, settled={}, notReady={}, errors={}",
                pending.size(), settled, notReady, errors);
        return new ReconcileSummary(pending.size(), settled, notReady, errors);
    }

    /**
     * 結算單一任務：撈 LINE 當日 total → 算 delta → 寫回 task + 更新 last_total。
     * 獨立 transaction，避免單一任務失敗影響其他任務。
     */
    @Transactional
    public SettleResult settleOne(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return SettleResult.SKIPPED;
        // race condition 防禦：可能上一輪同時跑、已寫入
        if (task.getLineDeliveredDelta() != null) return SettleResult.ALREADY_SETTLED;
        if (task.getFinishedAt() == null) return SettleResult.SKIPPED;

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
            return SettleResult.NOT_READY;
        }

        if (resp == null || resp.status() != NumberOfMessagesResponse.Status.READY) {
            log.info("LINE delivery 尚未 ready：taskId={}, date={}, status={}（下輪 scheduler 會重試）",
                    taskId, lineDate, resp == null ? "null" : resp.status());
            return SettleResult.NOT_READY;
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
        return SettleResult.SETTLED;
    }

    /** settleOne 的分支結果 */
    public enum SettleResult {
        /** 成功寫入 delta */
        SETTLED,
        /** LINE 還沒 ready / API 失敗，下輪 scheduler 會重試 */
        NOT_READY,
        /** 已結算過，本輪略過 */
        ALREADY_SETTLED,
        /** 任務不存在或 finishedAt 為空 */
        SKIPPED
    }

    /** 手動結算的回傳摘要 */
    public record ReconcileSummary(int scanned, int settled, int notReady, int errors) {}
}
