package com.linechatbot.controller;

import com.linecorp.bot.spring.boot.web.argument.annotation.LineBotDestination;
import com.linecorp.bot.spring.boot.web.argument.annotation.LineBotMessages;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.FollowEvent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UnfollowEvent;
import com.linechatbot.service.LineMessageService;
import com.linechatbot.service.LineUserService;
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
    private final LineUserService lineUserService;

    /**
     * POST /webhook
     * 接收 LINE 平台 Webhook 事件，必須在 5 秒內回應 200 OK
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @LineBotMessages List<Event> events,
            @LineBotDestination String destination) {

        for (Event event : events) {
            if (event instanceof FollowEvent followEvent) {
                String lineUserId = followEvent.source().userId();
                log.info("收到 LINE Follow 事件：userId={}", lineUserId);
                safelyOnFollow(lineUserId);

            } else if (event instanceof UnfollowEvent unfollowEvent) {
                String lineUserId = unfollowEvent.source().userId();
                log.info("收到 LINE Unfollow 事件：userId={}", lineUserId);
                safelyOnUnfollow(lineUserId);

            } else if (event instanceof MessageEvent messageEvent &&
                    messageEvent.message() instanceof TextMessageContent textContent) {

                String replyToken = messageEvent.replyToken();
                String lineUserId = messageEvent.source().userId();
                String userMessage = textContent.text();

                log.info("收到 LINE 訊息：userId={}, message={}", lineUserId, userMessage);

                // 用戶活躍度更新（兜底：若沒走 Follow event 也能補建）
                safelyTouchUser(lineUserId);

                // 非同步處理，立即返回讓 SDK 回應 200
                lineMessageService.handleTextMessage(replyToken, lineUserId, userMessage);
            } else {
                log.debug("忽略未處理事件：{}", event.getClass().getSimpleName());
            }
        }

        return ResponseEntity.ok().build();
    }

    /** 任一 user 服務呼叫出錯不可影響 Webhook 回應 */
    private void safelyOnFollow(String lineUserId) {
        try {
            lineUserService.onFollow(lineUserId);
        } catch (Exception e) {
            log.error("Follow 事件處理失敗：userId={}", lineUserId, e);
        }
    }

    private void safelyOnUnfollow(String lineUserId) {
        try {
            lineUserService.onUnfollow(lineUserId);
        } catch (Exception e) {
            log.error("Unfollow 事件處理失敗：userId={}", lineUserId, e);
        }
    }

    private void safelyTouchUser(String lineUserId) {
        try {
            lineUserService.touchOnMessage(lineUserId);
        } catch (Exception e) {
            log.warn("更新用戶活躍度失敗：userId={}, error={}", lineUserId, e.getMessage());
        }
    }
}
