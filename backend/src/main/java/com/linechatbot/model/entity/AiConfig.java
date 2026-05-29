package com.linechatbot.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * AI 串接設定（單例，DB 中固定只有一筆 id=1）。
 *
 * <p>啟動時：DataInitializer 確保有預設一筆（enabled=true、其他空）。
 * <p>查詢時：AiSettingsService.getEffectiveConfig 優先讀 DB 欄位，
 * 空值 fallback 到 application.yml 的 ${ai.openai.*} 環境變數。
 */
@Entity
@Table(name = "ai_config")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 主開關：false 時 AIService 直接跳過呼叫，OA 將只走 QA 規則 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** AI 廠商 識別字串，目前實際只支援 openai */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String provider = "openai";

    /** API key（DB 為主來源，空時 fallback env） */
    @Column(name = "api_key", length = 255)
    private String apiKey;

    /** Base URL，OpenAI 預設 https://api.openai.com/v1 */
    @Column(name = "base_url", length = 255)
    private String baseUrl;

    /** Model 名稱，例如 gpt-4o-mini */
    @Column(length = 100)
    private String model;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
