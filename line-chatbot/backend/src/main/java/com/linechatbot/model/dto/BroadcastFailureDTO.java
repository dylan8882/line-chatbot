package com.linechatbot.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 失敗 chunk 詳情（用於失敗清單查詢）
 */
@Data
public class BroadcastFailureDTO {
    private Long chunkId;
    private Integer chunkIndex;
    private Integer recipientCount;
    private Integer attempts;
    private String status;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime nextRetryAt;
    private String lineRequestId;
}
