package com.linechatbot.integration;

import com.linechatbot.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 黑名單整合測試
 * 使用 docker-compose 啟動的 Redis（localhost:6379），驗證登出後 Token 加入黑名單的行為。
 * 執行前需先啟動：docker-compose up redis -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class JwtBlacklistIntegrationTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";

    @Test
    @DisplayName("有效 Token：未登出前應通過驗證")
    void validToken_shouldPassValidation() {
        String token = jwtTokenProvider.generateToken("admin", "ADMIN");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("登出後 Token：應加入 Redis 黑名單且驗證失敗")
    void afterLogout_tokenShouldBeBlacklistedAndInvalid() {
        String token = jwtTokenProvider.generateToken("admin", "ADMIN");
        String jti = jwtTokenProvider.getJtiFromToken(token);

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();

        jwtTokenProvider.blacklistToken(token);

        assertThat(stringRedisTemplate.hasKey(JWT_BLACKLIST_PREFIX + jti)).isTrue();
        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("黑名單 Key 的 TTL 應大於 0（不永久保留）")
    void blacklistedToken_keyTTLShouldBePositive() {
        String token = jwtTokenProvider.generateToken("admin", "ADMIN");
        String jti = jwtTokenProvider.getJtiFromToken(token);

        jwtTokenProvider.blacklistToken(token);

        Long ttl = stringRedisTemplate.getExpire(JWT_BLACKLIST_PREFIX + jti);
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(3600L);
    }

    @Test
    @DisplayName("不同 Token 的黑名單應互相獨立")
    void differentTokens_shouldHaveIndependentBlacklists() {
        String token1 = jwtTokenProvider.generateToken("admin", "ADMIN");
        String token2 = jwtTokenProvider.generateToken("admin", "ADMIN");

        jwtTokenProvider.blacklistToken(token1);

        assertThat(jwtTokenProvider.validateToken(token1)).isFalse();
        assertThat(jwtTokenProvider.validateToken(token2)).isTrue();
    }

    @Test
    @DisplayName("可從 Token 正確解析 username 和 role")
    void generateToken_shouldContainCorrectClaims() {
        String token = jwtTokenProvider.generateToken("testuser", "ADMIN");

        assertThat(jwtTokenProvider.getUsernameFromToken(token)).isEqualTo("testuser");
        assertThat(jwtTokenProvider.getRoleFromToken(token)).isEqualTo("ADMIN");
        assertThat(jwtTokenProvider.getJtiFromToken(token)).isNotBlank();
    }
}
