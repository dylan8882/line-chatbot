package com.linechatbot.service;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linechatbot.model.entity.QAPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 收到 LINE 文字訊息後的處理鏈：rate limit → QA → AI fallback。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LineMessageService {

    private final QAService qaService;
    private final AIService aiService;
    private final UsageTrackingService usageTrackingService;
    private final MessagingApiClient messagingApiClient;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String RATE_LIMIT_PREFIX = "rate:";
    private static final long RATE_LIMIT_MAX = 100;
    private static final long RATE_LIMIT_TTL = 60; // 秒
    private static final String RATE_LIMIT_MSG = "您的訊息太頻繁，請稍後再試。";

    /** Webhook 已先 ACK，這裡只負責真的處理；例外吞掉只記 error。 */
    @Async("lineMessageExecutor")
    public void handleTextMessage(String replyToken, String lineUserId, String userMessage) {
        long startTime = System.currentTimeMillis();

        try {
            if (isRateLimited(lineUserId)) {
                sendReply(replyToken, RATE_LIMIT_MSG);
                return;
            }

            Optional<QAPair> qaMatch = qaService.findMatchingQA(userMessage);
            if (qaMatch.isPresent()) {
                QAPair qa = qaMatch.get();
                sendReply(replyToken, qa.getAnswer());
                int latency = (int) (System.currentTimeMillis() - startTime);
                usageTrackingService.logMessage(lineUserId, userMessage, qa.getAnswer(), "QA", qa.getId(), latency);
                return;
            }

            String aiResponse = aiService.getAIResponse(userMessage).block();
            if (aiResponse != null && !aiResponse.isBlank()) {
                sendReply(replyToken, aiResponse);
                int latency = (int) (System.currentTimeMillis() - startTime);
                usageTrackingService.logMessage(lineUserId, userMessage, aiResponse, "AI", null, latency);
            } else {
                usageTrackingService.logMessage(lineUserId, userMessage, null, "NONE", null, null);
            }

        } catch (Exception e) {
            log.error("處理 LINE 訊息失敗，userId={}，error={}", lineUserId, e.getMessage(), e);
        }
    }

    /** Fixed window：每分鐘 100 則，第一筆才 setExpire、之後純 INCR。 */
    private boolean isRateLimited(String lineUserId) {
        String key = RATE_LIMIT_PREFIX + lineUserId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, RATE_LIMIT_TTL, TimeUnit.SECONDS);
        }
        if (count != null && count > RATE_LIMIT_MAX) {
            log.warn("Rate limit 觸發：userId={}, count={}", lineUserId, count);
            return true;
        }
        return false;
    }

    private void sendReply(String replyToken, String text) {
        try {
            messagingApiClient.replyMessage(
                    new ReplyMessageRequest(replyToken, List.of(new TextMessage(text)), false)
            );
        } catch (Exception e) {
            log.error("LINE 回覆訊息失敗：{}", e.getMessage());
        }
    }
}
