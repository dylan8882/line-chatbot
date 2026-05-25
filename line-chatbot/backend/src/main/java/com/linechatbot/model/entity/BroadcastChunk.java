package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 推播分片：每片最多 500 個 LINE userId，對應一次 multicast API 呼叫
 */
@Entity
@Table(name = "broadcast_chunks", indexes = {
        @Index(name = "idx_chunk_task", columnList = "task_id, status"),
        @Index(name = "idx_chunk_status", columnList = "status, attempts")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 該片要發送的 LINE userId 陣列（JSON） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipient_ids", nullable = false)
    private String recipientIds;

    /** PENDING / SENDING / SUCCESS / FAILED / RETRYING */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 4;

    @Column(name = "line_request_id", length = 100)
    private String lineRequestId;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
