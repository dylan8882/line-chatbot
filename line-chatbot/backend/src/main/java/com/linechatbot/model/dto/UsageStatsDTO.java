package com.linechatbot.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 用量統計 DTO，用於 Dashboard API 回應
 */
@Data
@Builder
public class UsageStatsDTO {

    /** 今日收到訊息總數 */
    private Long totalMessages;

    /** QA 命中數 */
    private Long qaHits;

    /** AI 回覆數 */
    private Long aiReplies;

    /** 無回應數 */
    private Long noReply;

    /** QA 命中率（0~100） */
    private Double qaHitRate;

    /** 平均回應時間（毫秒） */
    private Double avgLatencyMs;

    /** 近 N 天每日訊息量趨勢 */
    private List<DailyStats> dailyStats;

    /**
     * 每日統計資料
     */
    @Data
    @Builder
    public static class DailyStats {
        private LocalDate date;
        private Long messageCount;
        private Long qaCount;
        private Long aiCount;
    }
}
