package com.linechatbot.service;

import com.linechatbot.model.dto.AiConfigDTO;
import com.linechatbot.model.entity.AiConfig;
import com.linechatbot.repository.AiConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 設定服務。
 *
 * <p><b>來源優先順序：</b> DB（ai_config 表）→ application.yml 的 ${ai.openai.*} 環境變數。
 * <p>查詢時 ({@link #getEffectiveConfig}) 自動 fallback；後台儲存只動 DB。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiSettingsService {

    private static final Long CONFIG_ID = 1L;

    private final AiConfigRepository configRepository;

    @Value("${ai.openai.api-key:}")
    private String envApiKey;

    @Value("${ai.openai.base-url:https://api.openai.com/v1}")
    private String envBaseUrl;

    @Value("${ai.openai.model:gpt-4o-mini}")
    private String envModel;

    /**
     * 取得後台顯示用設定（敏感欄位遮罩）。
     */
    public AiConfigDTO getConfig() {
        AiConfig config = configRepository.findById(CONFIG_ID)
                .orElseGet(AiConfig::new);
        return toDTO(config);
    }

    /**
     * 儲存設定。null = 不更新此欄位；空字串 = 清除。
     */
    @Transactional
    public AiConfigDTO saveConfig(AiConfigDTO dto) {
        AiConfig config = configRepository.findById(CONFIG_ID)
                .orElseGet(() -> {
                    AiConfig c = new AiConfig();
                    c.setId(CONFIG_ID);
                    return c;
                });

        if (dto.getEnabled() != null) config.setEnabled(dto.getEnabled());
        if (dto.getProvider() != null) config.setProvider(dto.getProvider().trim());
        // apiKey: null=不動；""=清除；其他=更新
        if (dto.getApiKey() != null) {
            config.setApiKey(dto.getApiKey().isBlank() ? null : dto.getApiKey().trim());
        }
        if (dto.getBaseUrl() != null) {
            config.setBaseUrl(dto.getBaseUrl().isBlank() ? null : dto.getBaseUrl().trim());
        }
        if (dto.getModel() != null) {
            config.setModel(dto.getModel().isBlank() ? null : dto.getModel().trim());
        }

        AiConfig saved = configRepository.save(config);
        log.info("AI 設定已更新：enabled={}, hasApiKey={}, model={}",
                saved.getEnabled(), StringUtils.hasText(saved.getApiKey()), saved.getModel());
        return toDTO(saved);
    }

    /**
     * 取得實際生效的設定值（給 {@link AIService} 使用）。
     * 每個欄位獨立 fallback：DB 有值用 DB，空則用 env。
     */
    public EffectiveConfig getEffectiveConfig() {
        AiConfig db = configRepository.findById(CONFIG_ID).orElse(null);

        boolean enabled = db == null || Boolean.TRUE.equals(db.getEnabled());
        String apiKey = pickNonBlank(db != null ? db.getApiKey() : null, envApiKey);
        String baseUrl = pickNonBlank(db != null ? db.getBaseUrl() : null, envBaseUrl);
        String model = pickNonBlank(db != null ? db.getModel() : null, envModel);

        return new EffectiveConfig(enabled, apiKey, baseUrl, model);
    }

    // ── 工具 ──────────────────────────────────────────────────

    private AiConfigDTO toDTO(AiConfig config) {
        AiConfigDTO dto = new AiConfigDTO();
        dto.setEnabled(config.getEnabled() != null ? config.getEnabled() : true);
        dto.setProvider(config.getProvider() != null ? config.getProvider() : "openai");
        dto.setApiKeyMasked(mask(config.getApiKey()));
        dto.setBaseUrl(config.getBaseUrl());
        dto.setModel(config.getModel());
        dto.setUpdatedAt(config.getUpdatedAt());
        dto.setIsConfigured(StringUtils.hasText(config.getApiKey()) || StringUtils.hasText(envApiKey));

        // 來源判斷：DB 有 apiKey → DB；否則若 env 有 → ENV；都沒有 → 空字串
        if (StringUtils.hasText(config.getApiKey())) {
            dto.setEffectiveSource("DB");
        } else if (StringUtils.hasText(envApiKey)) {
            dto.setEffectiveSource("ENV");
        } else {
            dto.setEffectiveSource("NONE");
        }
        return dto;
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) return null;
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }

    private String pickNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    /**
     * AIService 拿來判斷是否要呼叫 + 帶哪些參數的不可變 snapshot。
     */
    public record EffectiveConfig(boolean enabled, String apiKey, String baseUrl, String model) {
        public boolean isUsable() {
            return enabled && StringUtils.hasText(apiKey);
        }
    }
}
