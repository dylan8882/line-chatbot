package com.linechatbot.integration;

import com.linechatbot.model.dto.QAPairDTO;
import com.linechatbot.model.entity.QAPair;
import com.linechatbot.repository.QARepository;
import com.linechatbot.service.QAService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QA Cache 整合測試
 * 使用 docker-compose 啟動的 Redis（localhost:6379），驗證 Cache 寫入、命中與清除行為。
 * 執行前需先啟動：docker-compose up redis -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class QACacheIntegrationTest {

    @Autowired
    private QAService qaService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private QARepository qaRepository;

    private static final String QA_CACHE_KEY = "qa:list";

    @BeforeEach
    void setUp() {
        redisTemplate.delete(QA_CACHE_KEY);
    }

    @Test
    @DisplayName("Cache Miss：第一次查詢應從 DB 讀取並寫入 Cache")
    void findMatchingQA_cacheMiss_shouldQueryDbAndCacheResult() {
        QAPair qa = buildQA(1L, "營業時間", "CONTAINS");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("請問營業時間幾點？");

        assertThat(result).isPresent();
        assertThat(result.get().getKeyword()).isEqualTo("營業時間");
        verify(qaRepository, times(1)).findByIsActiveTrueOrderByPriorityDesc();

        // Cache 已寫入 Redis
        assertThat(redisTemplate.hasKey(QA_CACHE_KEY)).isTrue();
        assertThat(redisTemplate.getExpire(QA_CACHE_KEY)).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Cache Hit：第二次查詢應直接從 Cache 讀取，不打 DB")
    void findMatchingQA_cacheHit_shouldNotQueryDb() {
        QAPair qa = buildQA(1L, "營業時間", "CONTAINS");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        // 第一次：Cache Miss
        qaService.findMatchingQA("第一次查詢");
        // 第二次：Cache Hit
        qaService.findMatchingQA("請問營業時間幾點？");

        // DB 只被呼叫一次
        verify(qaRepository, times(1)).findByIsActiveTrueOrderByPriorityDesc();
    }

    @Test
    @DisplayName("新增 QA 後 Cache 應被清除")
    void createQA_shouldEvictCache() {
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of());
        qaService.findMatchingQA("先觸發 Cache 寫入");
        assertThat(redisTemplate.hasKey(QA_CACHE_KEY)).isTrue();

        QAPair saved = buildQA(2L, "新關鍵字", "EXACT");
        when(qaRepository.save(any())).thenReturn(saved);
        when(qaRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(saved)));

        QAPairDTO dto = new QAPairDTO();
        dto.setKeyword("新關鍵字");
        dto.setAnswer("新回答");
        dto.setMatchType("EXACT");
        qaService.createQA(dto);

        assertThat(redisTemplate.hasKey(QA_CACHE_KEY)).isFalse();
    }

    @Test
    @DisplayName("刪除 QA 後 Cache 應被清除")
    void deleteQA_shouldEvictCache() {
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of());
        qaService.findMatchingQA("先觸發 Cache 寫入");
        assertThat(redisTemplate.hasKey(QA_CACHE_KEY)).isTrue();

        when(qaRepository.existsById(1L)).thenReturn(true);
        qaService.deleteQA(1L);

        assertThat(redisTemplate.hasKey(QA_CACHE_KEY)).isFalse();
    }

    private QAPair buildQA(Long id, String keyword, String matchType) {
        return QAPair.builder()
                .id(id)
                .keyword(keyword)
                .answer("回答內容")
                .isActive(true)
                .priority(0)
                .matchType(matchType)
                .build();
    }
}
