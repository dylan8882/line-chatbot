package com.linechatbot.service;

import com.linechatbot.model.entity.MessageLog;
import com.linechatbot.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 用量追蹤服務，非同步寫入訊息紀錄
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageTrackingService {

    private final MessageLogRepository messageLogRepository;

    /**
     * 非同步寫入訊息紀錄
     *
     * @param lineUserId   LINE 用戶 ID
     * @param messageText  使用者輸入訊息
     * @param responseText 回覆內容
     * @param responseType 回覆類型（QA / AI / NONE）
     * @param qaId         若為 QA 回覆，對應的 QA ID
     * @param latencyMs    回應延遲（毫秒）
     */
    @Async("lineMessageExecutor")
    public void logMessage(String lineUserId,
                           String messageText,
                           String responseText,
                           String responseType,
                           Long qaId,
                           Integer latencyMs) {
        try {
            MessageLog log = MessageLog.builder()
                    .lineUserId(lineUserId)
                    .messageText(messageText)
                    .responseText(responseText)
                    .responseType(responseType)
                    .qaPairId(qaId)
                    .latencyMs(latencyMs)
                    .build();
            messageLogRepository.save(log);
        } catch (Exception e) {
            log.error("寫入訊息紀錄失敗：{}", e.getMessage());
        }
    }
}
