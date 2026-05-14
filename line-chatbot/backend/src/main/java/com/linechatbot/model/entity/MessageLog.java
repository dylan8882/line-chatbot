package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 訊息紀錄實體，記錄每次 LINE 訊息與回覆
 */
@Entity
@Table(name = "message_logs", indexes = {
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_line_user_id", columnList = "line_user_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LINE 平台用戶 ID */
    @Column(name = "line_user_id", nullable = false, length = 100)
    private String lineUserId;

    /** 用戶輸入的訊息 */
    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    /** 系統回覆的內容 */
    @Column(name = "response_text", columnDefinition = "TEXT")
    private String responseText;

    /**
     * 回覆類型：QA（問答比對）、AI（AI 回覆）、NONE（無回覆）
     */
    @Column(name = "response_type", nullable = false, length = 20)
    private String responseType;

    /** 命中的 QA 規則 ID（若有） */
    @Column(name = "qa_pair_id")
    private Long qaPairId;

    /** 處理延遲（毫秒） */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
