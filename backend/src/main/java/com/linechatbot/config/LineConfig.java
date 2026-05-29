package com.linechatbot.config;

import com.linechatbot.model.entity.LineChannelConfig;
import com.linechatbot.repository.LineChannelConfigRepository;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.parser.LineSignatureValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * LINE Bot SDK 相關設定。
 * 啟動時優先從 DB 讀取 Channel Secret / Access Token，
 * DB 無資料時 fallback 至環境變數（LINE_CHANNEL_SECRET / LINE_CHANNEL_TOKEN）。
 * 宣告為 AutoConfiguration 並在 SDK 的 LineBotWebMvcConfigurer 之後執行，
 * 確保我們的 bean 定義覆蓋 SDK 的預設值。
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.linecorp.bot.spring.boot.webmvc.configuration.LineBotWebMvcConfigurer")
@RequiredArgsConstructor
@Slf4j
public class LineConfig {

    private static final Long CONFIG_ID = 1L;

    @Value("${line.bot.channel-secret:}")
    private String envChannelSecret;

    @Value("${line.bot.channel-token:}")
    private String envChannelToken;

    private final LineChannelConfigRepository configRepository;

    @Bean
    public LineSignatureValidator lineSignatureValidator() {
        String secret = configRepository.findById(CONFIG_ID)
                .map(LineChannelConfig::getChannelSecret)
                .filter(StringUtils::hasText)
                .orElse(envChannelSecret);
        log.info("LineSignatureValidator 初始化：來源={}", resolveSource(secret, envChannelSecret));
        return new LineSignatureValidator(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    public MessagingApiClient messagingApiClient() {
        String token = configRepository.findById(CONFIG_ID)
                .map(LineChannelConfig::getChannelAccessToken)
                .filter(StringUtils::hasText)
                .orElse(envChannelToken);
        log.info("MessagingApiClient 初始化：來源={}", resolveSource(token, envChannelToken));
        return MessagingApiClient.builder(token).build();
    }

    private String resolveSource(String value, String envValue) {
        return StringUtils.hasText(value) && !value.equals(envValue) ? "DB" : "環境變數";
    }
}
