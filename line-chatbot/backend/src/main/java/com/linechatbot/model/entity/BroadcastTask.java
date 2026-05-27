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
 * 推播任務（每次推播一筆，含目標、訊息 snapshot、執行狀態統計）
 */
@Entity
@Table(name = "broadcast_tasks", indexes = {
        @Index(name = "idx_broadcast_status", columnList = "status, scheduled_at"),
        @Index(name = "idx_broadcast_created", columnList = "created_at DESC")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** 送出當下的訊息 snapshot（JSON 字串：LINE messages 物件陣列） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_content", nullable = false)
    private String messageContent;

    /** ALL / TAGS / USER_LIST */
    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    /** {tagIds:[...], match:"ANY|ALL"} 或 {userIds:[...]} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_filter")
    private String targetFilter;

    /**
     * LINE API 模式：PUSH（逐一發送，可取 per-user 結果）/ MULTICAST（批量發送，僅整批回報）。
     * 預設 PUSH；NARROWCAST target 不使用本欄位。
     */
    @Column(name = "api_mode", nullable = false, length = 20)
    @Builder.Default
    private String apiMode = "PUSH";

    /**
     * DRAFT / QUEUED / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "total_recipients", nullable = false)
    @Builder.Default
    private Integer totalRecipients = 0;

    @Column(name = "sent_count", nullable = false)
    @Builder.Default
    private Integer sentCount = 0;

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private Integer successCount = 0;

    @Column(name = "failed_count", nullable = false)
    @Builder.Default
    private Integer failedCount = 0;

    /**
     * LINE 平台日送達增量（僅 multicast task 結算後填寫）：
     * 任務完成 ≥ 5 分鐘後由 scheduler 撈 LINE delivery API 統計差異算出。
     * NULL = 尚未結算或非 multicast 任務。
     */
    @Column(name = "line_delivered_delta")
    private Long lineDeliveredDelta;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    /** A/B 測試組 ID（同一組 A/B 共用同一 abTestId，不同 variantLabel） */
    @Column(name = "ab_test_id", length = 64)
    private String abTestId;

    /** A/B 變體標籤："A" / "B"（也可以是 "Control"/"Variant1" 等） */
    @Column(name = "variant_label", length = 20)
    private String variantLabel;

    /** LINE Narrowcast API 回傳的 X-Line-Request-Id，用於後續查詢進度 */
    @Column(name = "narrowcast_request_id", length = 100)
    private String narrowcastRequestId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
