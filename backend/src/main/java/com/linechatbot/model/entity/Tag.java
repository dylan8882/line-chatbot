package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用戶分眾標籤
 */
@Entity
@Table(name = "tags")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** HEX 顏色，前端視覺辨識 */
    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#1677ff";

    @Column(length = 200)
    private String description;

    /** 反正規化欄位：擁有此標籤的用戶數，避免 COUNT(*) 全掃 */
    @Column(name = "user_count", nullable = false)
    @Builder.Default
    private Integer userCount = 0;

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
