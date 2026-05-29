package com.linechatbot.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate Limit 整合測試
 * 驗證 Redis INCR + EXPIRE 滑動視窗機制的實際行為。
 * 對應 LineMessageService.isRateLimited() 的邏輯。
 * 執行前需先啟動：docker-compose up redis -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String RATE_LIMIT_PREFIX = "rate:";
    private static final long RATE_LIMIT_MAX = 100;
    private static final long RATE_LIMIT_TTL = 60;
    private static final String TEST_USER = "U_test_rate_limit_user";

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RATE_LIMIT_PREFIX + TEST_USER);
    }

    @Test
    @DisplayName("第一次請求：計數應為 1 且 TTL 已設定")
    void firstRequest_shouldSetCountAndTTL() {
        String key = RATE_LIMIT_PREFIX + TEST_USER;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, RATE_LIMIT_TTL, TimeUnit.SECONDS);
        }

        assertThat(count).isEqualTo(1L);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(RATE_LIMIT_TTL);
    }

    @Test
    @DisplayName("重複請求：計數應正確累加，TTL 只在第一次設定")
    void multipleRequests_shouldIncrementWithoutResetTTL() {
        String key = RATE_LIMIT_PREFIX + TEST_USER;

        for (int i = 0; i < 10; i++) {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, RATE_LIMIT_TTL, TimeUnit.SECONDS);
            }
        }

        // INCR 後 get() 回傳 Number（小數字 Jackson 解析為 Integer，大數字為 Long）
        Number finalCount = (Number) redisTemplate.opsForValue().get(key);
        assertThat(finalCount).isNotNull();
        assertThat(finalCount.longValue()).isEqualTo(10L);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0L);
    }

    @Test
    @DisplayName("超過限制：計數超過 100 時應判定為 Rate Limited")
    void exceedRateLimit_shouldBeDetected() {
        String key = RATE_LIMIT_PREFIX + TEST_USER;

        Long lastCount = null;
        for (int i = 0; i < 101; i++) {
            lastCount = redisTemplate.opsForValue().increment(key);
            if (lastCount != null && lastCount == 1) {
                redisTemplate.expire(key, RATE_LIMIT_TTL, TimeUnit.SECONDS);
            }
        }

        assertThat(lastCount).isGreaterThan(RATE_LIMIT_MAX);
        boolean isRateLimited = lastCount != null && lastCount > RATE_LIMIT_MAX;
        assertThat(isRateLimited).isTrue();
    }

    @Test
    @DisplayName("TTL 過期後：計數應重置（視窗滑動）")
    void afterTTLExpires_countShouldReset() throws InterruptedException {
        String key = RATE_LIMIT_PREFIX + TEST_USER;

        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, 1, TimeUnit.SECONDS);

        Thread.sleep(1500);

        assertThat(redisTemplate.hasKey(key)).isFalse();

        Long newCount = redisTemplate.opsForValue().increment(key);
        assertThat(newCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("不同用戶的 Rate Limit 應互不影響")
    void differentUsers_shouldHaveIndependentRateLimits() {
        String user1Key = RATE_LIMIT_PREFIX + "user_A";
        String user2Key = RATE_LIMIT_PREFIX + "user_B";

        redisTemplate.delete(user1Key);
        redisTemplate.delete(user2Key);

        for (int i = 0; i < 50; i++) {
            Long c = redisTemplate.opsForValue().increment(user1Key);
            if (c != null && c == 1) redisTemplate.expire(user1Key, RATE_LIMIT_TTL, TimeUnit.SECONDS);
        }

        Long c = redisTemplate.opsForValue().increment(user2Key);
        if (c != null && c == 1) redisTemplate.expire(user2Key, RATE_LIMIT_TTL, TimeUnit.SECONDS);

        Number user1Count = (Number) redisTemplate.opsForValue().get(user1Key);
        Number user2Count = (Number) redisTemplate.opsForValue().get(user2Key);

        assertThat(user1Count).isNotNull();
        assertThat(user2Count).isNotNull();
        assertThat(user1Count.longValue()).isEqualTo(50L);
        assertThat(user2Count.longValue()).isEqualTo(1L);

        redisTemplate.delete(user1Key);
        redisTemplate.delete(user2Key);
    }
}
