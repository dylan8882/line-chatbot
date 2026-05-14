package com.linechatbot.config;

import org.springframework.context.annotation.Configuration;

/**
 * LINE Bot SDK 相關設定。
 * v9 起 MessagingApiClient 由 line-bot-spring-boot-webmvc 自動配置，
 * 僅需在 application.yml 設定 line.bot.channel-token 與 line.bot.channel-secret，
 * 無須手動宣告 Bean。
 */
@Configuration
public class LineConfig {
    // MessagingApiClient 與 Signature 驗證均由 line-bot-spring-boot-webmvc 自動配置
}
