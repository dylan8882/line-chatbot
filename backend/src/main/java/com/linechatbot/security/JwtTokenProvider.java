package com.linechatbot.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT Token 產生、驗證與黑名單管理
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expiration;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration,
            RedisTemplate<String, String> redisTemplate) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expiration = expiration;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 產生 JWT Token
     *
     * @param username 用戶名稱
     * @param role     用戶角色
     * @return JWT Token 字串
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .id(jti)
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 從 Token 取得用戶名稱
     */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 從 Token 取得角色
     */
    public String getRoleFromToken(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * 從 Token 取得 JTI（唯一識別碼）
     */
    public String getJtiFromToken(String token) {
        return parseClaims(token).getId();
    }

    /**
     * 驗證 Token 是否有效（簽章正確且未過期且未在黑名單中）
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String jti = claims.getId();
            // 檢查是否在黑名單中
            Boolean isBlacklisted = redisTemplate.hasKey(JWT_BLACKLIST_PREFIX + jti);
            return Boolean.FALSE.equals(isBlacklisted);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 驗證失敗：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 將 Token 加入黑名單（登出時使用）
     */
    public void blacklistToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String jti = claims.getId();
            long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMs > 0) {
                redisTemplate.opsForValue().set(
                        JWT_BLACKLIST_PREFIX + jti,
                        "blacklisted",
                        remainingMs,
                        TimeUnit.MILLISECONDS
                );
                log.info("Token JTI {} 已加入黑名單", jti);
            }
        } catch (JwtException e) {
            log.warn("無法將 Token 加入黑名單：{}", e.getMessage());
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
