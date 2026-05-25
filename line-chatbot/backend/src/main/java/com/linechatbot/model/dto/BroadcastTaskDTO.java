package com.linechatbot.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推播任務 DTO（後台查詢/列表用）
 */
@Data
public class BroadcastTaskDTO {

    private Long id;
    private String name;
    private String messageContent;
    private String targetType;
    private String targetFilter;
    private String status;

    private Integer totalRecipients;
    private Integer sentCount;
    private Integer successCount;
    private Integer failedCount;

    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 任務的所有 chunk 摘要（詳情頁用） */
    private List<ChunkSummary> chunks;

    @Data
    public static class ChunkSummary {
        private Long id;
        private Integer chunkIndex;
        private Integer recipientCount;
        private String status;
        private Integer attempts;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime sentAt;
    }
}
