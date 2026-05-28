package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * LINE Messaging API 頻道設定實體。
 * 採單例設計：資料庫中固定只有一筆（id = 1）。
 */
@Entity
@Table(name = "line_channel_config")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LineChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Channel ID（LINE Developers Console > Basic settings）
     */
    @Column(name = "channel_id", length = 50)
    private String channelId;

    /**
     * Channel Secret — 用於驗證 Webhook 簽章
     * （LINE Developers Console > Basic settings）
     */
    @Column(name = "channel_secret", length = 255)
    private String channelSecret;

    /**
     * Channel Access Token（Long-lived）— 用於呼叫 Messaging API
     * （LINE Developers Console > Messaging API > Channel access token）
     */
    @Column(name = "channel_access_token", length = 512)
    private String channelAccessToken;

    /**
     * 伺服器對外公開的 Base URL，用於產生 Webhook URL
     * 例如：https://example.com
     */
    @Column(name = "server_base_url", length = 255)
    private String serverBaseUrl;

    /**
     * 是否啟用 Webhook（對應 LINE Console 的「Use webhook」開關）
     */
    @Column(name = "webhook_enabled", nullable = false)
    @Builder.Default
    private Boolean webhookEnabled = true;

    /**
     * 是否停用 LINE OA 內建自動回覆訊息
     * 使用 Webhook 時建議設為 false
     */
    @Column(name = "auto_reply_enabled", nullable = false)
    @Builder.Default
    private Boolean autoReplyEnabled = false;

    /**
     * 是否啟用加入好友歡迎訊息（Greeting messages）
     */
    @Column(name = "greeting_enabled", nullable = false)
    @Builder.Default
    private Boolean greetingEnabled = true;

    /**
     * 加入好友歡迎訊息內容（純文字、≤ 500 字）。
     * Follow 事件處理時若 greetingEnabled 且本欄位非空，透過 push API 發送給用戶。
     */
    @Column(name = "greeting_message", length = 500)
    private String greetingMessage;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
