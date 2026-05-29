package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 問答配對實體，儲存 Chatbot 的問答規則
 */
@Entity
@Table(name = "qa_pairs")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QAPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 關鍵字，可為完整句子或關鍵詞 */
    @Column(nullable = false, length = 500)
    private String keyword;

    /** 回答內容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    /** 是否啟用 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** 優先順序，數字越高優先 */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    /**
     * 比對方式：EXACT（完全符合）、CONTAINS（包含）、REGEX（正規表達式）
     */
    @Column(name = "match_type", nullable = false, length = 20)
    @Builder.Default
    private String matchType = "CONTAINS";

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
