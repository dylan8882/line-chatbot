package com.linechatbot.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LINE Messaging API 頻道設定 DTO。
 *
 * <p>GET 時：channelSecret / channelAccessToken 回傳遮罩值（****xxxx），
 * 敏感欄位不完整揭露。
 *
 * <p>PUT 時：傳入 null 代表「不更新此欄位」，傳入空字串代表「清除」。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineChannelConfigDTO {

    /** 是否已完成基本設定（Channel Secret 與 Access Token 均已填寫） */
    private Boolean isConfigured;

    // ── 頻道基本資訊（LINE Developers Console > Basic settings） ──────────────

    /** Channel ID（數字字串，例如：1234567890） */
    private String channelId;

    /**
     * Channel Secret（遮罩）— 僅 GET 回傳，用於驗證 Webhook 簽章。
     * 格式：**** + 末 4 碼，例如 "****a1b2"
     */
    private String channelSecretMasked;

    /**
     * Channel Secret（明文）— 僅 PUT 時使用。
     * 傳入 null 代表不更新。
     */
    private String channelSecret;

    // ── Messaging API 設定（LINE Developers Console > Messaging API） ─────────

    /**
     * Channel Access Token（遮罩）— 僅 GET 回傳，用於呼叫 Messaging API。
     * 格式：**** + 末 4 碼。
     */
    private String channelAccessTokenMasked;

    /**
     * Channel Access Token（明文）— 僅 PUT 時使用。
     * 傳入 null 代表不更新。
     */
    private String channelAccessToken;

    // ── Webhook 設定 ──────────────────────────────────────────────────────────

    /**
     * 伺服器對外公開的 Base URL，用於產生 Webhook URL。
     * 例如：https://example.com
     */
    private String serverBaseUrl;

    /**
     * Webhook URL（唯讀，由後端計算）。
     * 值為 serverBaseUrl + "/webhook"，需填入 LINE Developers Console。
     */
    private String webhookUrl;

    /** 是否啟用 Webhook（對應 LINE Console 的「Use webhook」開關） */
    private Boolean webhookEnabled;

    // ── LINE OA 行為設定 ───────────────────────────────────────────────────────

    /**
     * 是否啟用 LINE OA 內建自動回覆訊息。
     * 使用 Webhook 時建議關閉（false），以免與 Chatbot 回覆衝突。
     */
    private Boolean autoReplyEnabled;

    /**
     * 是否啟用加入好友歡迎訊息（Greeting messages）。
     */
    private Boolean greetingEnabled;

    /** 最後更新時間 */
    private LocalDateTime updatedAt;
}
