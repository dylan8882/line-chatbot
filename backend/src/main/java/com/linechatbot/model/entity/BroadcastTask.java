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

    /**
     * 推播目標類型：
     * <ul>
     *   <li>ALL — 全部已加好友</li>
     *   <li>TAGS — 依標籤（搭配 targetFilter.tagIds / tagMatch）</li>
     *   <li>USER_LIST — 指定用戶（搭配 targetFilter.userIds）</li>
     *   <li>NARROWCAST — 走 LINE 官方大規模分發 API，audience 由 LINE 平台自管</li>
     * </ul>
     */
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
     * 任務狀態：
     * <ul>
     *   <li>DRAFT — 草稿（剛建立，未提交）</li>
     *   <li>QUEUED — 排隊中（部分 flow 用，目前較少出現）</li>
     *   <li>SCHEDULED — 已排程（scheduledAt 未到）</li>
     *   <li>RUNNING — 執行中（已分片並推入 Stream，worker 處理中）</li>
     *   <li>PAUSED — 已暫停（保留位，現階段未實作暫停）</li>
     *   <li>COMPLETED — 已完成（所有 chunk 都到終態）</li>
     *   <li>FAILED — 失敗（提交時即沒有收件人 / 所有 chunk 失敗）</li>
     *   <li>CANCELLED — 已取消</li>
     * </ul>
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
