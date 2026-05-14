package com.linechatbot.service;

import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.QAPairDTO;
import com.linechatbot.model.entity.QAPair;
import com.linechatbot.repository.QARepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 問答配對服務，處理 QA 的 CRUD 操作與訊息比對邏輯
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QAService {

    private final QARepository qaRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String QA_CACHE_KEY = "qa:list";
    private static final long QA_CACHE_TTL = 3600; // 1 小時

    /**
     * 根據使用者訊息比對 QA 規則（EXACT / CONTAINS / REGEX）
     * 優先比對 priority 較高的規則
     *
     * @param userMessage 使用者輸入訊息
     * @return 匹配的 QAPair，若無匹配則回傳 empty
     */
    public Optional<QAPair> findMatchingQA(String userMessage) {
        List<QAPair> qaList = getActiveQAList();

        for (QAPair qa : qaList) {
            if (matches(userMessage, qa)) {
                log.debug("QA 命中：keyword={}, type={}", qa.getKeyword(), qa.getMatchType());
                return Optional.of(qa);
            }
        }
        return Optional.empty();
    }

    /**
     * 取得啟用中的 QA 列表（優先從 Redis Cache 讀取）
     */
    @SuppressWarnings("unchecked")
    private List<QAPair> getActiveQAList() {
        Object cached = redisTemplate.opsForValue().get(QA_CACHE_KEY);
        if (cached instanceof List) {
            return (List<QAPair>) cached;
        }
        List<QAPair> list = qaRepository.findByIsActiveTrueOrderByPriorityDesc();
        redisTemplate.opsForValue().set(QA_CACHE_KEY, list, QA_CACHE_TTL, TimeUnit.SECONDS);
        return list;
    }

    /**
     * 根據 matchType 比對訊息
     */
    private boolean matches(String userMessage, QAPair qa) {
        String keyword = qa.getKeyword();
        return switch (qa.getMatchType()) {
            case "EXACT" -> userMessage.equals(keyword);
            case "CONTAINS" -> userMessage.contains(keyword);
            case "REGEX" -> {
                try {
                    yield Pattern.compile(keyword).matcher(userMessage).find();
                } catch (PatternSyntaxException e) {
                    log.warn("QA ID {} 的 REGEX 語法錯誤：{}", qa.getId(), e.getMessage());
                    yield false;
                }
            }
            default -> false;
        };
    }

    /**
     * 分頁查詢所有 QA
     */
    public Page<QAPairDTO> getAllQA(Pageable pageable) {
        return qaRepository.findAll(pageable).map(this::toDTO);
    }

    /**
     * 新增 QA
     */
    @Transactional
    public QAPairDTO createQA(QAPairDTO dto) {
        QAPair qa = QAPair.builder()
                .keyword(dto.getKeyword())
                .answer(dto.getAnswer())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .priority(dto.getPriority() != null ? dto.getPriority() : 0)
                .matchType(dto.getMatchType())
                .build();
        QAPair saved = qaRepository.save(qa);
        evictQACache();
        return toDTO(saved);
    }

    /**
     * 修改 QA
     */
    @Transactional
    public QAPairDTO updateQA(Long id, QAPairDTO dto) {
        QAPair qa = qaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("QAPair", id));
        qa.setKeyword(dto.getKeyword());
        qa.setAnswer(dto.getAnswer());
        if (dto.getIsActive() != null) qa.setIsActive(dto.getIsActive());
        if (dto.getPriority() != null) qa.setPriority(dto.getPriority());
        if (dto.getMatchType() != null) qa.setMatchType(dto.getMatchType());
        evictQACache();
        return toDTO(qa);
    }

    /**
     * 刪除 QA
     */
    @Transactional
    public void deleteQA(Long id) {
        if (!qaRepository.existsById(id)) {
            throw new ResourceNotFoundException("QAPair", id);
        }
        qaRepository.deleteById(id);
        evictQACache();
    }

    /**
     * 切換 QA 啟用狀態
     */
    @Transactional
    public QAPairDTO toggleQA(Long id) {
        QAPair qa = qaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("QAPair", id));
        qa.setIsActive(!qa.getIsActive());
        evictQACache();
        return toDTO(qa);
    }

    /**
     * 清除 Redis QA Cache
     */
    public void evictQACache() {
        redisTemplate.delete(QA_CACHE_KEY);
        log.debug("QA Cache 已清除");
    }

    private QAPairDTO toDTO(QAPair qa) {
        QAPairDTO dto = new QAPairDTO();
        dto.setId(qa.getId());
        dto.setKeyword(qa.getKeyword());
        dto.setAnswer(qa.getAnswer());
        dto.setIsActive(qa.getIsActive());
        dto.setPriority(qa.getPriority());
        dto.setMatchType(qa.getMatchType());
        dto.setCreatedAt(qa.getCreatedAt());
        dto.setUpdatedAt(qa.getUpdatedAt());
        return dto;
    }
}
