package com.linechatbot.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 快取設定
 * 使用 Lettuce 連線池提升高併發效能
 * 配置 QA 快取（1小時）和 Rate Limit 快取（1分鐘）
 */
@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${redis.ttl.qa-cache:3600}")
    private long qaCacheTtl;

    @Value("${redis.ttl.rate-limit:60}")
    private long rateLimitTtl;

    /**
     * 建立 Redis 連線工廠（使用 Lettuce 連線池）
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(5);

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    /**
     * 建立 JSON 序列化器。
     * 使用自訂序列化器搭配 WRAPPER_ARRAY 型別策略。
     *
     * 問題根源：Jackson 的 mapper.writeValueAsBytes(list) 在根層級不會加 type wrapper，
     * 因為沒有「宣告型別」上下文。改用 mapper.writerFor(Object.class).writeValueAsBytes(source)
     * 強制告知 Jackson 宣告型別為 Object，進而觸發 WRAPPER_ARRAY 包裝 List<T> 等集合型別，
     * 使其可正確 round-trip。
     */
    private RedisSerializer<Object> jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.WRAPPER_ARRAY
        );
        return new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object source) throws SerializationException {
                if (source == null) return new byte[0];
                try {
                    return mapper.writerFor(Object.class).writeValueAsBytes(source);
                } catch (JsonProcessingException e) {
                    throw new SerializationException("Could not write JSON: " + e.getMessage(), e);
                }
            }

            @Override
            public Object deserialize(byte[] source) throws SerializationException {
                if (source == null || source.length == 0) return null;
                try {
                    return mapper.readValue(source, Object.class);
                } catch (Exception e) {
                    throw new SerializationException("Could not read JSON: " + e.getMessage(), e);
                }
            }
        };
    }

    /**
     * 泛型 RedisTemplate，支援 String key 和 Object value（JSON 序列化）
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        RedisSerializer<Object> serializer = jsonSerializer();
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 設定不同 Cache 名稱的 TTL
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("qa-list", defaultConfig.entryTtl(Duration.ofSeconds(qaCacheTtl)));
        cacheConfigurations.put("rate-limit", defaultConfig.entryTtl(Duration.ofSeconds(rateLimitTtl)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
