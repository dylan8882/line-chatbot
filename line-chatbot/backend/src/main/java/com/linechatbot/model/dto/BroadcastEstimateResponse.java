package com.linechatbot.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推播任務預估收件人數的回應
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastEstimateResponse {
    /** 預估的收件人數 */
    private long totalRecipients;
    /** 預估的 chunk 數（500 人/批） */
    private int totalChunks;
}
