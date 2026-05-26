package com.linechatbot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推播 Worker 管理者：啟動時開 N 個 worker 執行緒，
 * 每個 worker 跑無限迴圈讀取 Stream、交給 ChunkProcessor 處理、ACK 訊息。
 *
 * <p>所有 worker 屬於同一個 consumer group，由 Redis 自動分配訊息（at-most-once-per-consumer，
 * 但若 worker crash 訊息會留在 PEL，由 {@link BroadcastDeadLetterScheduler} 重新派發）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BroadcastWorkerManager {

    private final BroadcastQueueService queueService;
    private final BroadcastChunkProcessor chunkProcessor;

    @Qualifier("broadcastWorkerExecutor")
    private final Executor workerExecutor;

    @Value("${broadcast.workers.count:4}")
    private int workerCount;

    /**
     * XREADGROUP 阻塞時間。必須**短於** Lettuce 的 commandTimeout（RedisConfig 設 5s），
     * 否則正常 BLOCK 等空會被當 Redis command timeout 拋出 RedisCommandTimeoutException。
     * 預設 4000ms 留 1 秒 buffer。
     */
    @Value("${broadcast.workers.block-ms:4000}")
    private long blockMs;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        running.set(true);
        for (int i = 0; i < workerCount; i++) {
            final String workerId = "worker-" + i;
            workerExecutor.execute(() -> runLoop(workerId));
        }
        log.info("已啟動 {} 個 broadcast workers", workerCount);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        log.info("Broadcast worker 收到停止訊號");
    }

    /** Worker 主迴圈：XREADGROUP → process → XACK */
    private void runLoop(String workerId) {
        log.info("Broadcast worker 啟動：{}", workerId);
        while (running.get()) {
            try {
                MapRecord<String, Object, Object> record = queueService.readNext(workerId, blockMs);
                if (record == null) continue; // 阻塞超時，正常情況

                Long chunkId = queueService.parseChunkId(record);
                if (chunkId != null) {
                    chunkProcessor.process(chunkId);
                }
                queueService.acknowledge(record.getId());
            } catch (org.springframework.dao.QueryTimeoutException timeout) {
                // Lettuce commandTimeout 觸發（BLOCK 等空時的正常邊界情況）；不噴 ERROR
                log.debug("Worker {} XREADGROUP timeout，重試", workerId);
            } catch (Exception e) {
                log.error("Worker {} 迴圈例外：{}", workerId, e.getMessage(), e);
                sleep(500);
            }
        }
        log.info("Broadcast worker 停止：{}", workerId);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
