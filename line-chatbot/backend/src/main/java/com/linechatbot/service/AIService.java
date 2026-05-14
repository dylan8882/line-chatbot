package com.linechatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AI 服務，透過 OpenAI API 取得自然語言回應
 * 支援非同步呼叫，逾時 10 秒，發生錯誤時回傳預設訊息
 */
@Service
@Slf4j
public class AIService {

    private final WebClient webClient;
    private final String model;
    private static final String DEFAULT_FALLBACK = "抱歉，我目前無法處理您的問題，請稍後再試或聯絡客服。";

    public AIService(
            @Value("${ai.openai.api-key}") String apiKey,
            @Value("${ai.openai.base-url}") String baseUrl,
            @Value("${ai.openai.model}") String model) {
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 非同步呼叫 OpenAI Chat Completion API
     * 逾時 10 秒，出錯時回傳 fallback 訊息
     *
     * @param userMessage 使用者輸入訊息
     * @return AI 回覆的 Mono
     */
    public Mono<String> getAIResponse(String userMessage) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一個友善的客服助理，請用繁體中文回答。"),
                        Map.of("role", "user", "content", userMessage)
                ),
                "max_tokens", 500,
                "temperature", 0.7
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::extractContent)
                .onErrorReturn(DEFAULT_FALLBACK)
                .doOnError(e -> log.error("AI API 呼叫失敗：{}", e.getMessage()));
    }

    /**
     * 從 OpenAI 回應中提取訊息內容
     */
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
