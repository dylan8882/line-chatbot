package com.linechatbot.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 推播成效統計回應
 */
@Data
@Builder
public class BroadcastStatisticsDTO {

    private Long taskId;
    private String status;

    private int totalRecipients;
    private int totalChunks;

    private int successChunks;
    private int failedChunks;
    private int retryingChunks;
    private int pendingChunks;

    /** 成功送達的收件人數（= 各成功 chunk 收件人總和） */
    private int deliveredRecipients;

    /** 成功率 0.0–1.0 */
    private double successRate;

    /** 平均嘗試次數（包含成功與失敗） */
    private double avgAttempts;

    /** 任務開始到完成的時間（毫秒）；未完成則為 null */
    private Long durationMs;

    /** 每秒發送速率（已完成或目前正進行） */
    private double sendRatePerSecond;

    /** 失敗錯誤碼分布：{errorCode → count} */
    private List<ErrorBucket> errorBreakdown;

    @Data
    @Builder
    public static class ErrorBucket {
        private String errorCode;
        private int count;
    }
}
