package com.linechatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * LINE Chatbot 主應用程式入口點
 * 啟用非同步處理以支援高併發 LINE Webhook 處理
 */
@SpringBootApplication
@EnableAsync
public class LineChatbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(LineChatbotApplication.class, args);
    }
}
