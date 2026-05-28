package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * LINE 用戶資料，於 Follow 事件時建立，Unfollow 時更新狀態
 */
@Entity
@Table(name = "line_users", indexes = {
        @Index(name = "idx_line_users_status", columnList = "status"),
        @Index(name = "idx_line_users_followed_at", columnList = "followed_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LineUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LINE 平台用戶 ID（U 開頭 33 字） */
    @Column(name = "line_user_id", nullable = false, unique = true, length = 100)
    private String lineUserId;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "picture_url", length = 500)
    private String pictureUrl;

    @Column(name = "status_message", length = 255)
    private String statusMessage;

    @Column(length = 10)
    private String language;

    /**
     * 用戶狀態：
     * <ul>
     *   <li>FOLLOWED — 已加好友（可推播）</li>
     *   <li>BLOCKED — 已封鎖 / 退追（無法再收到推播）</li>
     * </ul>
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "FOLLOWED";

    @Column(name = "followed_at")
    private LocalDateTime followedAt;

    @Column(name = "unfollowed_at")
    private LocalDateTime unfollowedAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_tags",
            joinColumns = @JoinColumn(name = "line_user_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();
}
