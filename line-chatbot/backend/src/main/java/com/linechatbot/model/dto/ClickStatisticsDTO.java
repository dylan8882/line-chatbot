package com.linechatbot.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 任務級點擊統計
 */
@Data
@Builder
public class ClickStatisticsDTO {

    private Long taskId;

    /** 該任務的所有點擊次數總和 */
    private long totalClicks;

    /** 不重複 IP 數（粗略 unique click） */
    private long uniqueIps;

    /** 該任務的送達人數，用於計算 CTR */
    private int deliveredRecipients;

    /** Click-Through Rate (totalClicks / deliveredRecipients)；deliveredRecipients=0 時 0 */
    private double ctr;

    /** 每個 tracking link 的細節 */
    private List<LinkStat> links;

    @Data
    @Builder
    public static class LinkStat {
        private Long linkId;
        private Integer linkIndex;
        private String targetUrl;
        private int clickCount;
    }
}
