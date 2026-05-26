package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 單次點擊事件
 */
@Entity
@Table(name = "click_events", indexes = {
        @Index(name = "idx_click_events_task", columnList = "task_id, created_at"),
        @Index(name = "idx_click_events_link", columnList = "link_id, created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    /** 反正規化：避免 join click_links 才能查 task */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** IPv4/IPv6 都能放 (45 字元上限) */
    @Column(length = 45)
    private String ip;

    @Column(length = 500)
    private String referer;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
