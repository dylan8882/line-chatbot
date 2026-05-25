package com.linechatbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重試排程器：每秒檢查 Redis sorted set 中已到期的 chunk，
 * 將其重新推入 stream 等待 worker 處理。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BroadcastRetryScheduler {

    private final BroadcastQueueService queueService;

    /**
     * 每秒掃描一次到期的重試 chunk。
     * 一次最多取出 200 個避免單次處理過久。
     */
    @Scheduled(fixedDelayString = "${broadcast.retry.scan-interval-ms:1000}")
    public void requeueDueChunks() {
        long now = System.currentTimeMillis();
        List<Long> due = queueService.pollDueRetries(now, 200);
        if (due.isEmpty()) return;

        for (Long chunkId : due) {
            queueService.enqueue(chunkId);
        }
        log.info("重新排入 {} 個到期的重試 chunk", due.size());
    }
}
