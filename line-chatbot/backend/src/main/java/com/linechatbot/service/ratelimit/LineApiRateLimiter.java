package com.linechatbot.service.ratelimit;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LINE API 速率限制器，使用 Redis Lua token bucket 跨實例共享配額。
 *
 * <p>Lua 腳本保證 原子性：refill 與 consume 在同一個 Redis round trip 內完成，
 * 避免多 worker 同時取 token 時的競態條件。
 *
 * <p>multicast 預設 60 req/min（容量 60、每秒 refill 1 個 token），
 * 比 LINE 給的限制保守，留 buffer 避免 429。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LineApiRateLimiter {

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local last = tonumber(data[2])
            if tokens == nil then
                tokens = capacity
                last = now
            end

            local elapsed = math.max(0, now - last)
            tokens = math.min(capacity, tokens + elapsed * refill_per_ms)

            local granted = 0
            if tokens >= 1 then
                tokens = tokens - 1
                granted = 1
            end

            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, 3600)
            return granted
            """;

    private final StringRedisTemplate redisTemplate;

    @Value("${broadcast.rate-limit.multicast.capacity:60}")
    private int multicastCapacity;

    /** 每秒可補充的 token 數（multicast 每秒 1 個 → 60 req/min） */
    @Value("${broadcast.rate-limit.multicast.refill-per-second:1}")
    private double multicastRefillPerSecond;

    private DefaultRedisScript<Long> script;

    @PostConstruct
    public void init() {
        script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
        log.info("LineApiRateLimiter 已初始化：multicast capacity={}, refill={}/s",
                multicastCapacity, multicastRefillPerSecond);
    }

    /**
     * 嘗試取得一個 multicast token。
     *
     * @return true = 成功取得；false = 配額用盡，呼叫方應 backoff
     */
    public boolean tryAcquireMulticast() {
        return tryAcquire("rate:multicast", multicastCapacity, multicastRefillPerSecond / 1000.0);
    }

    /**
     * 阻塞式取得 token：失敗則 sleep 後重試，最多等 waitMaxMs 毫秒。
     *
     * @return true = 已取得；false = 等待超時
     */
    public boolean acquireMulticast(long waitMaxMs) {
        long deadline = System.currentTimeMillis() + waitMaxMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquireMulticast()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean tryAcquire(String key, int capacity, double refillPerMs) {
        Long granted = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillPerMs),
                String.valueOf(System.currentTimeMillis())
        );
        return granted != null && granted == 1L;
    }
}
