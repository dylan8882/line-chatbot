package com.linechatbot.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 串接設定 DTO。
 *
 * <p>GET 時：apiKey 回傳遮罩值（{@code apiKeyMasked}），不揭露完整 key。
 * <p>PUT 時：欄位傳 null = 不更新；空字串 = 清除（之後 fallback 回 env）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiConfigDTO {

    /** 主開關，false 時 AI 不會被呼叫（OA 只走 QA 規則） */
    private Boolean enabled;

    /** 廠商（目前只支援 openai） */
    private String provider;

    /** API key 遮罩 — 僅 GET 回傳。格式："****" + 末 4 碼 */
    private String apiKeyMasked;

    /** API key 明文 — 僅 PUT 使用。null=不更新；""=清除 */
    private String apiKey;

    /** Base URL（例：https://api.openai.com/v1） */
    private String baseUrl;

    /** Model（例：gpt-4o-mini） */
    private String model;

    /** 是否已完成基本設定（apiKey 已填） */
    private Boolean isConfigured;

    /** 實際生效的來源："DB" 或 "ENV"（給 UI 顯示提示用） */
    private String effectiveSource;

    private LocalDateTime updatedAt;
}
