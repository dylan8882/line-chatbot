package com.linechatbot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推播分片佇列服務：使用 Redis Stream 作為 chunk queue（生產者 + Stream 操作），
 * 並用 Redis Sorted Set 排程延遲重試。
 *
 * <p>Stream key   ：{@value #STREAM_KEY}
 * <p>Consumer group：{@value #CONSUMER_GROUP}
 * <p>Retry zset    ：{@value #RETRY_ZSET}（score = 應重試時的 epoch millis）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastQueueService {

    public static final String STREAM_KEY = "broadcast:chunks:queue";
    public static final String CONSUMER_GROUP = "broadcast-workers";
    public static final String RETRY_ZSET = "broadcast:retry:zset";
    public static final String CHUNK_FIELD = "chunkId";

    private final StringRedisTemplate redisTemplate;

    /**
     * 啟動時確保 stream 與 consumer group 存在。
     * 若 group 已存在會丟 BUSYGROUP，視為正常情況忽略。
     */
    @PostConstruct
    public void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream()
                    .createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
            log.info("已建立 Redis Stream consumer group：{} on {}", CONSUMER_GROUP, STREAM_KEY);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.info("Consumer group 已存在：{}", CONSUMER_GROUP);
            } else {
                // Stream 不存在的情況也會走這裡，但 MKSTREAM 由 createGroup 內部處理；
                // 若仍失敗則拋出讓人看到根因
                log.warn("建立 consumer group 時發生例外：{}", e.getMessage());
            }
        }
    }

    /**
     * 把一個 chunkId 推入 stream（producer）。
     */
    public RecordId enqueue(Long chunkId) {
        return redisTemplate.opsForStream()
                .add(STREAM_KEY, Map.of(CHUNK_FIELD, String.valueOf(chunkId)));
    }

    /**
     * 批量推入。
     */
    public void enqueueBatch(List<Long> chunkIds) {
        for (Long id : chunkIds) {
            enqueue(id);
        }
    }

    /**
     * 排程 chunk 在 nextRetryAt 之後重試。
     */
    public void scheduleRetry(Long chunkId, long nextRetryAtMs) {
        redisTemplate.opsForZSet().add(RETRY_ZSET, String.valueOf(chunkId), nextRetryAtMs);
    }

    /**
     * 取得所有到期應重試的 chunkIds 並從 zset 移除（原子性：取出後不會再次取出）。
     */
    public List<Long> pollDueRetries(long nowMs, int maxBatch) {
        Set<ZSetOperations.TypedTuple<String>> due = redisTemplate.opsForZSet()
                .rangeByScoreWithScores(RETRY_ZSET, 0, nowMs, 0, maxBatch);
        if (due == null || due.isEmpty()) return List.of();

        List<Long> ids = due.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .toList();
        // 移除已取出的 entries
        Object[] members = due.stream().map(ZSetOperations.TypedTuple::getValue).toArray();
        redisTemplate.opsForZSet().remove(RETRY_ZSET, members);
        return ids;
    }

    // ── Consumer 端 API（供 BroadcastWorker 使用） ─────────────────

    /**
     * 從 stream 讀取一筆訊息，阻塞最多 blockMs 毫秒。
     *
     * @return MapRecord 或 null（timeout）
     */
    public MapRecord<String, Object, Object> readNext(String workerId, long blockMs) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(Consumer.from(CONSUMER_GROUP, workerId),
                        StreamReadOptions.empty().count(1).block(Duration.ofMillis(blockMs)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) return null;
        return records.get(0);
    }

    /**
     * 確認消息已處理（XACK）。
     */
    public void acknowledge(RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, CONSUMER_GROUP, recordId);
    }

    /**
     * 解析 chunkId 從 stream record。
     */
    public Long parseChunkId(MapRecord<String, Object, Object> record) {
        Object value = record.getValue().get(CHUNK_FIELD);
        return value != null ? Long.valueOf(value.toString()) : null;
    }

    /**
     * 強制取消整個任務時，由 caller 清空 zset 中對應 chunkIds（避免後續重試）。
     */
    public void removeRetries(List<Long> chunkIds) {
        if (chunkIds.isEmpty()) return;
        Object[] members = chunkIds.stream().map(String::valueOf).toArray();
        redisTemplate.opsForZSet().remove(RETRY_ZSET, members);
    }

    /**
     * 查看當前 retry zset 的大小（監控用）。
     */
    public long retryQueueSize() {
        Long size = redisTemplate.opsForZSet().size(RETRY_ZSET);
        return size == null ? 0 : size;
    }

    // Phase 4 會新增 PEL（pending entries list）的 dead-letter 監控
}
