package com.linechatbot.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 推播進度事件（透過 Redis Pub/Sub 廣播 → SseEmitter → 前端 EventSource）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BroadcastProgressEvent {

    /** 事件類型：PROGRESS / COMPLETED / FAILED / CANCELLED */
    private String type;

    private Long taskId;
    private String status;

    private Integer sentCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer totalRecipients;

    /** chunk 級事件時可帶入 */
    private Long chunkId;

    /** epoch millis */
    private Long timestamp;
}
