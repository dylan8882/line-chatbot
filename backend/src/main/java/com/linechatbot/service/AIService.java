package com.linechatbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 呼叫 OpenAI Chat Completion 取回覆。設定走 {@link AiSettingsService}（DB 優先、env fallback），
 * 主開關關閉時 {@link #getAIResponse} 直接回 {@link Mono#empty()}、呼叫端走 NONE 流程。
 *
 * <p>WebClient 每次呼叫即時建構，讓後台改 API key / model 不用重啟就生效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIService {

    private static final String DEFAULT_FALLBACK = "抱歉，我目前無法處理您的問題，請稍後再試或聯絡客服。";

    private final AiSettingsService settingsService;

    /**
     * 非同步呼叫 Chat Completion API。
     *
     * <ul>
     *   <li>主開關關閉 或 沒設定 API key → 回 {@link Mono#empty()}（OA 走「無回應」流程）</li>
     *   <li>API 呼叫失敗（401/timeout/5xx 等）→ 先記錯誤 log，再回 {@link #DEFAULT_FALLBACK}</li>
     * </ul>
     */
    public Mono<String> getAIResponse(String userMessage) {
        AiSettingsService.EffectiveConfig cfg = settingsService.getEffectiveConfig();
        if (!cfg.isUsable()) {
            log.debug("AI 未啟用或未設定 key，跳過：enabled={}, hasKey={}",
                    cfg.enabled(), cfg.apiKey() != null && !cfg.apiKey().isBlank());
            return Mono.empty();
        }

        Map<String, Object> requestBody = Map.of(
                "model", cfg.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一個友善的客服助理，請用繁體中文回答。"),
                        Map.of("role", "user", "content", userMessage)
                ),
                "max_tokens", 500,
                "temperature", 0.7
        );

        WebClient client = WebClient.builder()
                .baseUrl(cfg.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        return client.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::extractContent)
                // 順序：doOnError 先記 log → onErrorReturn 再吃掉錯誤
                // 原本順序顛倒，導致 onErrorReturn 先攔截、doOnError 永遠不觸發、出問題時看不到 log。
                .doOnError(e -> log.error("AI API 呼叫失敗：{}", e.getMessage()))
                .onErrorReturn(DEFAULT_FALLBACK);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            log.error("解析 AI 回應失敗：{}", e.getMessage());
            return DEFAULT_FALLBACK;
        }
    }
}
