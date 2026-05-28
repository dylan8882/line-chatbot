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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** QA 規則的 CRUD 與訊息比對；啟用清單走 Redis cache。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QAService {

    private final QARepository qaRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String QA_CACHE_KEY = "qa:list";
    private static final long QA_CACHE_TTL = 3600; // 1 小時

    /** 依 priority desc 順序比對，第一個 match 就 return；無命中回 empty。 */
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

    /** cache hit 走 Redis、miss 才打 DB；寫回時走 evictAfterCommit 維持一致性。 */
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

    public Page<QAPairDTO> getAllQA(Pageable pageable) {
        return qaRepository.findAll(pageable).map(this::toDTO);
    }

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
        evictAfterCommit();
        return toDTO(saved);
    }

    @Transactional
    public QAPairDTO updateQA(Long id, QAPairDTO dto) {
        QAPair qa = qaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("QAPair", id));
        qa.setKeyword(dto.getKeyword());
        qa.setAnswer(dto.getAnswer());
        if (dto.getIsActive() != null) qa.setIsActive(dto.getIsActive());
        if (dto.getPriority() != null) qa.setPriority(dto.getPriority());
        if (dto.getMatchType() != null) qa.setMatchType(dto.getMatchType());
        evictAfterCommit();
        return toDTO(qa);
    }

    @Transactional
    public void deleteQA(Long id) {
        if (!qaRepository.existsById(id)) {
            throw new ResourceNotFoundException("QAPair", id);
        }
        qaRepository.deleteById(id);
        evictAfterCommit();
    }

    @Transactional
    public QAPairDTO toggleQA(Long id) {
        QAPair qa = qaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("QAPair", id));
        qa.setIsActive(!qa.getIsActive());
        evictAfterCommit();
        return toDTO(qa);
    }

    /**
     * 清除 Redis QA Cache。
     *
     * <p>留作 public 供外部需要時手動清除（例：批次匯入後）。一般 CRUD 內部請走
     * {@link #evictAfterCommit()} 以避免 cache vs transaction commit 的 race。
     */
    public void evictQACache() {
        redisTemplate.delete(QA_CACHE_KEY);
        log.debug("QA Cache 已清除");
    }

    /**
     * 把 evictQACache 延後到 transaction commit 後執行，避免：
     * <ol>
     *   <li>T1 寫入 QA（TX 未 commit）→ evictQACache 立刻刪 Redis 鍵</li>
     *   <li>T2 並發查 findMatchingQA → cache miss → 從 DB 讀（看不到 T1 未 commit 的新資料）→
     *       <b>把舊資料塞回 cache</b></li>
     *   <li>T1 TX commit（新資料落 DB）</li>
     *   <li>之後查 findMatchingQA → cache hit 舊資料 → 新 QA 永遠看不到</li>
     * </ol>
     *
     * <p>沒有 transaction 同步上下文（如純單元測試直接呼叫）時 fallback 為立即 evict。
     */
    private void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictQACache();
                }
            });
        } else {
            evictQACache();
        }
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
