package com.linechatbot.controller;

import com.linecorp.bot.spring.boot.web.argument.annotation.LineBotDestination;
import com.linecorp.bot.spring.boot.web.argument.annotation.LineBotMessages;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linechatbot.service.LineMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LINE Webhook 事件處理器
 * 使用 LINE Bot SDK v9 的 @LineBotMessages / @LineBotDestination 接收 Webhook 事件
 * SDK 自動驗證 X-Line-Signature；收到訊息後立即回應，實際處理非同步執行
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class LineWebhookController {

    private final LineMessageService lineMessageService;

    /**
     * POST /webhook
     * 接收 LINE 平台 Webhook 事件，必須在 5 秒內回應 200 OK
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @LineBotMessages List<Event> events,
            @LineBotDestination String destination) {

        for (Event event : events) {
            if (event instanceof MessageEvent messageEvent &&
                    messageEvent.message() instanceof TextMessageContent textContent) {

                String replyToken = messageEvent.replyToken();
                String lineUserId = messageEvent.source().userId();
                String userMessage = textContent.text();

                log.info("收到 LINE 訊息：userId={}, message={}", lineUserId, userMessage);

                // 非同步處理，立即返回讓 SDK 回應 200
                lineMessageService.handleTextMessage(replyToken, lineUserId, userMessage);
            } else {
                log.debug("忽略非文字事件：{}", event.getClass().getSimpleName());
            }
        }

        return ResponseEntity.ok().build();
    }
}
