package com.linechatbot.service;

import com.linechatbot.model.dto.LineChannelConfigDTO;
import com.linechatbot.model.entity.LineChannelConfig;
import com.linechatbot.repository.LineChannelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * LINE Messaging API 頻道設定服務。
 * 讀取 / 儲存 Channel ID、Channel Secret、Channel Access Token 等設定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LineSettingsService {

    private static final Long CONFIG_ID = 1L;
    private static final String LINE_API_BASE = "https://api.line.me";

    private final LineChannelConfigRepository configRepository;
    private final WebClient.Builder webClientBuilder;

    /**
     * 取得目前頻道設定（敏感欄位遮罩）。
     */
    public LineChannelConfigDTO getConfig() {
        LineChannelConfig config = configRepository.findById(CONFIG_ID)
                .orElseGet(LineChannelConfig::new);
        return toDTO(config);
    }

    /**
     * 儲存頻道設定。
     * DTO 中 channelSecret / channelAccessToken 為 null 時保留原值。
     */
    @Transactional
    public LineChannelConfigDTO saveConfig(LineChannelConfigDTO dto) {
        LineChannelConfig config = configRepository.findById(CONFIG_ID)
                .orElseGet(() -> {
                    LineChannelConfig c = new LineChannelConfig();
                    c.setId(CONFIG_ID);
                    return c;
                });

        if (dto.getChannelId() != null) {
            config.setChannelId(dto.getChannelId().trim());
        }
        // null = 不更新；空字串 = 清除
        if (dto.getChannelSecret() != null) {
            config.setChannelSecret(dto.getChannelSecret().isBlank() ? null : dto.getChannelSecret().trim());
        }
        if (dto.getChannelAccessToken() != null) {
            config.setChannelAccessToken(dto.getChannelAccessToken().isBlank() ? null : dto.getChannelAccessToken().trim());
        }
        if (dto.getServerBaseUrl() != null) {
            String base = dto.getServerBaseUrl().trim();
            // 移除末尾斜線
            config.setServerBaseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        }
        if (dto.getWebhookEnabled() != null) {
            config.setWebhookEnabled(dto.getWebhookEnabled());
        }
        if (dto.getAutoReplyEnabled() != null) {
            config.setAutoReplyEnabled(dto.getAutoReplyEnabled());
        }
        if (dto.getGreetingEnabled() != null) {
            config.setGreetingEnabled(dto.getGreetingEnabled());
        }
        // null = 不更新；空字串 = 清除
        if (dto.getGreetingMessage() != null) {
            String msg = dto.getGreetingMessage().trim();
            config.setGreetingMessage(msg.isEmpty() ? null : msg);
        }

        LineChannelConfig saved = configRepository.save(config);
        log.info("LINE 頻道設定已更新");
        return toDTO(saved);
    }

    /**
     * 驗證 Channel Access Token 是否有效（呼叫 LINE Bot info API）。
     * 使用同步回傳，避免在 Spring MVC 環境下因 Mono 切換 thread 造成 SecurityContext 丟失。
     *
     * @return 驗證結果訊息
     */
    public String verifyAccessToken() {
        return configRepository.findById(CONFIG_ID)
                .filter(c -> StringUtils.hasText(c.getChannelAccessToken()))
                .map(c -> {
                    try {
                        String body = webClientBuilder.build()
                                .get()
                                .uri(LINE_API_BASE + "/v2/bot/info")
                                .header("Authorization", "Bearer " + c.getChannelAccessToken())
                                .retrieve()
                                .bodyToMono(String.class)
                                .timeout(Duration.ofSeconds(10))
                                .block();
                        return "驗證成功：" + body;
                    } catch (Exception e) {
                        log.warn("LINE Access Token 驗證失敗：{}", e.getMessage());
                        return "驗證失敗：" + e.getMessage();
                    }
                })
                .orElse("尚未設定 Channel Access Token");
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private LineChannelConfigDTO toDTO(LineChannelConfig config) {
        LineChannelConfigDTO dto = new LineChannelConfigDTO();
        dto.setChannelId(config.getChannelId());
        dto.setChannelSecretMasked(mask(config.getChannelSecret()));
        dto.setChannelAccessTokenMasked(mask(config.getChannelAccessToken()));

        String base = config.getServerBaseUrl();
        dto.setServerBaseUrl(base);
        dto.setWebhookUrl(StringUtils.hasText(base) ? base + "/webhook" : null);

        dto.setWebhookEnabled(config.getWebhookEnabled() != null ? config.getWebhookEnabled() : true);
        dto.setAutoReplyEnabled(config.getAutoReplyEnabled() != null ? config.getAutoReplyEnabled() : false);
        dto.setGreetingEnabled(config.getGreetingEnabled() != null ? config.getGreetingEnabled() : true);
        dto.setGreetingMessage(config.getGreetingMessage());
        dto.setUpdatedAt(config.getUpdatedAt());

        boolean configured = StringUtils.hasText(config.getChannelSecret())
                && StringUtils.hasText(config.getChannelAccessToken());
        dto.setIsConfigured(configured);

        return dto;
    }

    /**
     * 遮罩敏感字串，僅顯示末 4 碼，其餘以 **** 替代。
     */
    private String mask(String value) {
        if (!StringUtils.hasText(value)) return null;
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }
}
