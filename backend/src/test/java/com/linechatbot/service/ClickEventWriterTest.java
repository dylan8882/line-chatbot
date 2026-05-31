package com.linechatbot.service;

import com.linechatbot.model.entity.ClickEvent;
import com.linechatbot.repository.ClickEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ClickEventWriter 單元測試。
 *
 * <p>@Async / @Transactional 在純單測（無 Spring context）下不會生效，這裡只驗證方法
 * <b>邏輯</b>：save event 後一定要 Redis INCR click_count + SADD dirty set；
 * save 例外時不能漏寫 warn 也不能向上拋。
 *
 * <p>真正的 proxy 行為與 ClickCountFlushScheduler 整合需要整合測試，留待後續補。
 */
@ExtendWith(MockitoExtension.class)
class ClickEventWriterTest {

    @Mock ClickEventRepository eventRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;
    @InjectMocks ClickEventWriter writer;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("正常情境：save event 後 Redis INCR + dirty set 加入該 linkId")
    void recordEvent_normal_savesAndBuffers() {
        writer.recordEvent(5L, 15L, "Mozilla/5.0", "127.0.0.1", "https://line.me/");

        ArgumentCaptor<ClickEvent> eventCap = ArgumentCaptor.forClass(ClickEvent.class);
        verify(eventRepository).save(eventCap.capture());
        assertThat(eventCap.getValue().getLinkId()).isEqualTo(5L);
        assertThat(eventCap.getValue().getTaskId()).isEqualTo(15L);
        assertThat(eventCap.getValue().getIp()).isEqualTo("127.0.0.1");

        verify(valueOps).increment("click:count:5");
        verify(setOps).add("click:dirty:links", "5");
    }

    @Test
    @DisplayName("UA / referer 超長會被 truncate 不報錯")
    void recordEvent_truncatesLongFields() {
        String longUa = "x".repeat(800);
        String longReferer = "y".repeat(800);

        writer.recordEvent(1L, 1L, longUa, "127.0.0.1", longReferer);

        ArgumentCaptor<ClickEvent> cap = ArgumentCaptor.forClass(ClickEvent.class);
        verify(eventRepository).save(cap.capture());
        assertThat(cap.getValue().getUserAgent()).hasSize(500);
        assertThat(cap.getValue().getReferer()).hasSize(500);
    }

    @Test
    @DisplayName("event save 失敗：吞例外不向上拋，且不應碰 Redis 計數器")
    void recordEvent_saveThrows_swallowsAndSkipsCounter() {
        doThrow(new RuntimeException("DB down")).when(eventRepository).save(any());

        writer.recordEvent(1L, 1L, null, null, null);

        verifyNoInteractions(valueOps);
        verifyNoInteractions(setOps);
    }
}
