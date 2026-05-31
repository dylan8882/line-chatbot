package com.linechatbot.service;

import com.linechatbot.repository.ClickLinkRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 把 {@link ClickEventWriter} 累積在 Redis 的 click_count 增量批次刷回 DB。
 *
 * <p>仿 {@link BroadcastCounterService#flushDirtyTasks} 同樣的 Redis buffer + scheduled flush
 * pattern。對應 dirty link IDs 用 Lua 原子腳本「讀取 + 歸零」，避免 flush 期間到來的
 * INCR 被覆蓋丟失（新 INCR 會落在歸零後的 key、下次 flush 再被吃進去）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClickCountFlushScheduler {

    public static final String DIRTY_SET = "click:dirty:links";
    public static final String COUNT_KEY_PREFIX = "click:count:";

    /** GET 當前值、SET 為 0 並回傳原值；空或 0 直接回 0 不寫。 */
    private static final String SCRIPT_DRAIN = """
            local cnt = redis.call('GET', KEYS[1])
            if cnt == false or cnt == '0' then
                return 0
            end
            redis.call('SET', KEYS[1], '0')
            return tonumber(cnt)
            """;

    private final StringRedisTemplate redisTemplate;
    private final ClickLinkRepository linkRepository;

    private DefaultRedisScript<Long> drainScript;

    @PostConstruct
    public void init() {
        drainScript = new DefaultRedisScript<>(SCRIPT_DRAIN, Long.class);
    }

    public static String countKey(Long linkId) {
        return COUNT_KEY_PREFIX + linkId;
    }

    /**
     * 每 5 秒掃 dirty set、對每個 linkId Lua 原子取 delta 後 UPDATE DB。
     * 整個方法包在一個 transaction 內：若中途某筆失敗，已 drain 的 delta 不會回滾到 Redis
     * （由 Lua 已經歸零）、會被 log warn 並丟失。對行銷統計的精度容忍度足夠。
     */
    @Scheduled(fixedDelayString = "${click.count.flush-interval-ms:5000}")
    @Transactional
    public void flushDirtyLinks() {
        Set<String> dirty = redisTemplate.opsForSet().members(DIRTY_SET);
        if (dirty == null || dirty.isEmpty()) return;

        int flushed = 0;
        for (String s : dirty) {
            try {
                Long linkId = Long.valueOf(s);
                Long delta = redisTemplate.execute(drainScript, List.of(countKey(linkId)));
                if (delta != null && delta > 0) {
                    linkRepository.addClickCount(linkId, delta);
                    flushed++;
                }
                redisTemplate.opsForSet().remove(DIRTY_SET, s);
            } catch (Exception e) {
                log.warn("Flush click_count 失敗：linkId={}, error={}", s, e.getMessage());
            }
        }
        if (flushed > 0) {
            log.debug("ClickCount flushed：{} 個 link", flushed);
        }
    }
}
