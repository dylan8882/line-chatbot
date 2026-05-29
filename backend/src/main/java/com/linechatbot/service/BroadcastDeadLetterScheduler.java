package com.linechatbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Dead-letter 處理器：
 *
 * <p>Worker 崩潰時，正在處理的訊息會留在 Redis Stream 的 PEL（Pending Entries List），
 * 永遠不會被 ACK，也不會自動派發給其他 worker。本排程器定期掃描 PEL，
 * 對 idle 過久（{@code broadcast.deadletter.idle-ms}）的訊息：
 *
 * <ol>
 *   <li>XCLAIM 取得所有權，分派給 dead-letter consumer。</li>
 *   <li>呼叫 {@link BroadcastChunkProcessor#process(Long)} 重新處理。</li>
 *   <li>處理完成後 XACK 從 PEL 移除。</li>
 * </ol>
 *
 * <p>若重新處理仍失敗，訊息會留在 dead-letter consumer 的 PEL，下一輪繼續被認領，
 * 由 ChunkProcessor 的 maxAttempts 機制最終標記為 FAILED。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BroadcastDeadLetterScheduler {

    private static final String DEAD_LETTER_CONSUMER = "dead-letter-handler";

    private final BroadcastQueueService queueService;
    private final BroadcastChunkProcessor chunkProcessor;

    /** PEL 訊息超過此 idle 時間就會被視為 dead-letter（預設 60 秒） */
    @Value("${broadcast.deadletter.idle-ms:60000}")
    private long idleMs;

    /** 每次最多處理多少筆，避免單次過久 */
    @Value("${broadcast.deadletter.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${broadcast.deadletter.scan-interval-ms:60000}")
    public void scanAndReclaim() {
        var summary = queueService.pendingSummary();
        if (summary == null || summary.getTotalPendingMessages() == 0) return;

        log.info("PEL 中有 {} 個未 ACK 訊息，開始檢查 idle 時間", summary.getTotalPendingMessages());
        List<PendingMessage> details = queueService.pendingDetails(batchSize);

        int reclaimed = 0;
        for (PendingMessage pm : details) {
            if (pm.getElapsedTimeSinceLastDelivery().toMillis() < idleMs) continue;

            try {
                MapRecord<String, Object, Object> record = queueService.claim(
                        DEAD_LETTER_CONSUMER, Duration.ofMillis(idleMs), pm.getId());
                if (record == null) continue;

                Long chunkId = queueService.parseChunkId(record);
                if (chunkId == null) {
                    queueService.acknowledge(pm.getId());
                    continue;
                }

                log.warn("Dead-letter 重新處理：chunkId={}, idleMs={}, deliveryCount={}",
                        chunkId, pm.getElapsedTimeSinceLastDelivery().toMillis(),
                        pm.getTotalDeliveryCount());

                chunkProcessor.process(chunkId);
                queueService.acknowledge(pm.getId());
                reclaimed++;
            } catch (Exception e) {
                log.error("Dead-letter 處理 {} 失敗", pm.getIdAsString(), e);
                // 不 ACK，下輪再試
            }
        }
        if (reclaimed > 0) log.info("Dead-letter 已重新處理 {} 個訊息", reclaimed);
    }
}
