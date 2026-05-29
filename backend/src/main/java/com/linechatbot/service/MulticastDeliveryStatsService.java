package com.linechatbot.service;

import com.linechatbot.model.entity.MulticastDailyDelivery;
import com.linechatbot.repository.MulticastDailyDeliveryRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.NumberOfMessagesResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 查 LINE 平台「當日 multicast 累計送達數」。
 *
 * <p><b>為什麼不做 per-task delta？</b>
 * LINE 對此端點的統計通常要等到次日才 READY（一天延遲），而當 scheduler 第二天
 * 拉到資料時，同日所有 multicast task 都已經完成 → 第一個被結算的會吃光當日總數、
 * 後續 task 拿到 0，無法精準歸因。故改成「儀表板層級顯示當日總數」，
 * 不假裝每個 task 都有自己的 delivered 數。
 *
 * <p>{@code multicast_daily_delivery} 表保留作為快取：
 * 同一天頻繁查詢時 5 分鐘內走快取，避免打爆 LINE API。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MulticastDeliveryStatsService {

    private static final DateTimeFormatter LINE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 快取存活時間：當日資料 5 分鐘內走快取（過去日期已 READY 後實質永久有效） */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final MulticastDailyDeliveryRepository dailyRepository;
    private final MessagingApiClient messagingApiClient;

    /**
     * 走快取優先：cache 命中且未過期直接回；否則打 LINE API、僅 status=READY 才寫回快取
     * （未 ready 的 response 不寫，避免把 stale UNREADY 鎖在快取裡）。
     */
    @Transactional
    public DailyDelivery getDailyTotal(LocalDate date) {
        Optional<MulticastDailyDelivery> cached = dailyRepository.findById(date);
        if (cached.isPresent() && !isStale(cached.get())) {
            MulticastDailyDelivery c = cached.get();
            return new DailyDelivery(date, c.getLastTotal(), "READY", c.getUpdatedAt(), true);
        }

        String lineDate = date.format(LINE_DATE_FMT);
        NumberOfMessagesResponse resp;
        try {
            Result<NumberOfMessagesResponse> result = messagingApiClient
                    .getNumberOfSentMulticastMessages(lineDate)
                    .get();
            resp = result.body();
        } catch (Exception e) {
            log.warn("查詢 LINE multicast delivery 失敗：date={}, error={}", lineDate, e.getMessage());
            // 失敗時回快取舊值（若有），讓前端至少有資料展示
            return cached
                    .map(c -> new DailyDelivery(date, c.getLastTotal(), "ERROR", c.getUpdatedAt(), true))
                    .orElse(new DailyDelivery(date, null, "ERROR", null, false));
        }

        if (resp == null || resp.status() != NumberOfMessagesResponse.Status.READY) {
            String status = resp == null ? "ERROR" : resp.status().name();
            log.info("LINE delivery 尚未 ready：date={}, status={}", lineDate, status);
            return cached
                    .map(c -> new DailyDelivery(date, c.getLastTotal(), status, c.getUpdatedAt(), true))
                    .orElse(new DailyDelivery(date, null, status, null, false));
        }

        long total = resp.success() == null ? 0L : resp.success();
        MulticastDailyDelivery entity = cached.orElseGet(() -> MulticastDailyDelivery.builder()
                .date(date)
                .lastTotal(0L)
                .build());
        entity.setLastTotal(total);
        MulticastDailyDelivery saved = dailyRepository.save(entity);
        log.info("LINE delivery 已更新：date={}, total={}", lineDate, total);
        return new DailyDelivery(date, total, "READY", saved.getUpdatedAt(), false);
    }

    private boolean isStale(MulticastDailyDelivery entity) {
        return entity.getUpdatedAt() == null
                || entity.getUpdatedAt().isBefore(LocalDateTime.now().minus(CACHE_TTL));
    }

    /**
     * 查詢結果 DTO。
     *
     * @param date       查詢日期
     * @param total      LINE 回的當日累計送達數（READY 時有值；UNREADY 且無歷史快取時為 null）
     * @param status     LINE 統計狀態：READY / UNREADY / UNAVAILABLE_FOR_PRIVACY / OUT_OF_SERVICE
     *                   / UNDEFINED / ERROR（網路或解析失敗）
     * @param asOf       本筆資料的時間戳（從快取或本次更新）
     * @param fromCache  是否來自快取（false = 本次剛打 LINE 拿到）
     */
    public record DailyDelivery(
            LocalDate date,
            Long total,
            String status,
            LocalDateTime asOf,
            boolean fromCache
    ) {}
}
