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
 * 訊息模板，可重複使用的 LINE 訊息內容（Text / Flex / Image / Template）
 */
@Entity
@Table(name = "message_templates")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** TEXT / FLEX / IMAGE / TEMPLATE */
    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType;

    /**
     * LINE messages 物件陣列的 JSON 字串，發送時直接附加為 multicast 請求的 messages 欄位。
     * 用 SqlTypes.JSON 讓 Hibernate 自動處理 MySQL JSON 與 PostgreSQL JSONB 的差異。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String content;

    @Column(length = 500)
    private String thumbnail;

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
