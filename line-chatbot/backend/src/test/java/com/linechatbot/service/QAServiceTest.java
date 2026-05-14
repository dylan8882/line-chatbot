package com.linechatbot.service;

import com.linechatbot.model.entity.QAPair;
import com.linechatbot.repository.QARepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * QAService 單元測試
 */
@ExtendWith(MockitoExtension.class)
class QAServiceTest {

    @Mock
    private QARepository qaRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private QAService qaService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null); // Cache Miss
    }

    @Test
    @DisplayName("EXACT 比對：完全相符時應命中")
    void findMatchingQA_exactMatch_shouldHit() {
        QAPair qa = buildQA("你好", "EXACT");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("你好");

        assertThat(result).isPresent();
        assertThat(result.get().getKeyword()).isEqualTo("你好");
    }

    @Test
    @DisplayName("EXACT 比對：部分相符時不應命中")
    void findMatchingQA_exactMatch_shouldNotHitOnPartial() {
        QAPair qa = buildQA("你好", "EXACT");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("你好嗎");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("CONTAINS 比對：包含關鍵字時應命中")
    void findMatchingQA_containsMatch_shouldHit() {
        QAPair qa = buildQA("營業時間", "CONTAINS");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("請問你們的營業時間是幾點？");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("REGEX 比對：符合正規表達式時應命中")
    void findMatchingQA_regexMatch_shouldHit() {
        QAPair qa = buildQA("訂單\\d+", "REGEX");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("我的訂單12345狀態");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("REGEX 比對：語法錯誤時不應拋出例外")
    void findMatchingQA_invalidRegex_shouldNotThrow() {
        QAPair qa = buildQA("[invalid regex", "REGEX");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("測試訊息");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("無匹配時應回傳 empty")
    void findMatchingQA_noMatch_shouldReturnEmpty() {
        QAPair qa = buildQA("特定關鍵字", "CONTAINS");
        when(qaRepository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(qa));

        Optional<QAPair> result = qaService.findMatchingQA("完全不相關的訊息");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("新增 QA 後應清除 Cache")
    void createQA_shouldEvictCache() {
        when(qaRepository.save(any())).thenReturn(buildQA("test", "CONTAINS"));
        var dto = new com.linechatbot.model.dto.QAPairDTO();
        dto.setKeyword("test");
        dto.setAnswer("回答");
        dto.setMatchType("CONTAINS");

        qaService.createQA(dto);

        verify(redisTemplate).delete("qa:list");
    }

    private QAPair buildQA(String keyword, String matchType) {
        return QAPair.builder()
                .id(1L)
                .keyword(keyword)
                .answer("回答內容")
                .isActive(true)
                .priority(0)
                .matchType(matchType)
                .build();
    }
}
