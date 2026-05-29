package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 點擊追蹤連結：每個推播任務內出現的 URL 都會在送出前建立一筆，
 * 訊息中的 button URL 改寫為 /c/{token}。
 */
@Entity
@Table(name = "click_links", indexes = {
        @Index(name = "idx_click_links_task", columnList = "task_id, link_index")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClickLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 該 URL 在訊息中的順序（0-based） */
    @Column(name = "link_index", nullable = false)
    private Integer linkIndex;

    /** 原始目標 URL */
    @Column(name = "target_url", nullable = false, length = 2000)
    private String targetUrl;

    /** 短 token（url-safe 隨機字串） */
    @Column(nullable = false, unique = true, length = 32)
    private String token;

    /** 反正規化：累計點擊次數，避免每次都 COUNT(*) */
    @Column(name = "click_count", nullable = false)
    @Builder.Default
    private Integer clickCount = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
