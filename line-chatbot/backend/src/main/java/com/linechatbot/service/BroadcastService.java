package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.config.BroadcastConfig;
import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.BroadcastCreateRequest;
import com.linechatbot.model.dto.BroadcastEstimateResponse;
import com.linechatbot.model.dto.BroadcastTaskDTO;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.model.entity.MessageTemplate;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.LineUserRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 推播任務生命週期管理：建立、預估、提交執行、取消。
 * 實際 multicast 呼叫委派給 {@link BroadcastDispatchService}（非同步）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastService {

    private final BroadcastTaskRepository taskRepository;
    private final BroadcastChunkRepository chunkRepository;
    private final LineUserRepository lineUserRepository;
    private final MessageTemplateService templateService;
    private final BroadcastQueueService queueService;
    private final BroadcastCounterService counterService;
    private final MessagingApiClient messagingApiClient;
    private final ObjectMapper objectMapper;

    private static final Set<String> ALLOWED_TARGET_TYPES = Set.of("ALL", "TAGS", "USER_LIST");

    /**
     * 分頁查詢任務。
     */
    public Page<BroadcastTaskDTO> list(String status, Pageable pageable) {
        String st = (status != null && !status.isBlank()) ? status : null;
        return taskRepository.search(st, pageable).map(this::toDTO);
    }

    /**
     * 任務詳情（含所有 chunk 摘要）。
     */
    public BroadcastTaskDTO getDetail(Long id) {
        BroadcastTask task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", id));
        return toDetailDTO(task);
    }

    /**
     * 預估收件人數（不建立任何資料）。
     */
    public BroadcastEstimateResponse estimate(BroadcastCreateRequest req) {
        List<String> recipients = computeRecipients(req);
        int chunks = (int) Math.ceil(recipients.size() / (double) BroadcastConfig.CHUNK_SIZE);
        return new BroadcastEstimateResponse(recipients.size(), chunks);
    }

    /**
     * 建立任務（DRAFT 狀態）。冪等：相同 idempotencyKey 直接回傳已存在的任務。
     */
    @Transactional
    public BroadcastTaskDTO create(BroadcastCreateRequest req) {
        validateTargetType(req.getTargetType());

        if (req.getIdempotencyKey() != null && !req.getIdempotencyKey().isBlank()) {
            Optional<BroadcastTask> existing = taskRepository.findByIdempotencyKey(req.getIdempotencyKey());
            if (existing.isPresent()) {
                log.info("idempotencyKey 命中，回傳既有任務：id={}", existing.get().getId());
                return toDTO(existing.get());
            }
        }

        String messageContent = resolveMessageContent(req);
        validateMessageContent(messageContent);

        BroadcastTask task = BroadcastTask.builder()
                .name(req.getName())
                .messageContent(messageContent)
                .targetType(req.getTargetType())
                .targetFilter(serializeTargetFilter(req))
                .scheduledAt(req.getScheduledAt())
                .idempotencyKey(req.getIdempotencyKey())
                .status("DRAFT")
                .build();

        BroadcastTask saved = taskRepository.save(task);
        log.info("建立推播任務：id={}, name={}, targetType={}", saved.getId(), saved.getName(), saved.getTargetType());
        return toDTO(saved);
    }

    /**
     * 提交任務執行：計算收件人、建立 chunks、啟動非同步派發。
     */
    @Transactional
    public BroadcastTaskDTO submit(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));

        if (!"DRAFT".equals(task.getStatus()) && !"QUEUED".equals(task.getStatus())) {
            throw new IllegalArgumentException("任務狀態無法提交：" + task.getStatus());
        }

        // 計算當下的收件人快照
        BroadcastCreateRequest fakeReq = new BroadcastCreateRequest();
        fakeReq.setTargetType(task.getTargetType());
        applyTargetFilterToRequest(task.getTargetFilter(), fakeReq);
        List<String> recipients = computeRecipients(fakeReq);

        if (recipients.isEmpty()) {
            task.setStatus("FAILED");
            task.setErrorMessage("沒有符合條件的收件人");
            task.setFinishedAt(LocalDateTime.now());
            return toDTO(task);
        }

        // 切片建立 chunks 並收集 ID
        int idx = 0;
        List<Long> chunkIds = new ArrayList<>();
        for (int i = 0; i < recipients.size(); i += BroadcastConfig.CHUNK_SIZE) {
            List<String> slice = recipients.subList(i, Math.min(i + BroadcastConfig.CHUNK_SIZE, recipients.size()));
            BroadcastChunk chunk = BroadcastChunk.builder()
                    .taskId(task.getId())
                    .chunkIndex(idx++)
                    .recipientIds(toJson(slice))
                    .status("PENDING")
                    .build();
            BroadcastChunk saved = chunkRepository.save(chunk);
            chunkIds.add(saved.getId());
        }

        task.setTotalRecipients(recipients.size());
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        task.setSentCount(0);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        taskRepository.save(task);

        // 初始化 Redis 計數器（INCR 從 0 開始）
        counterService.initTask(task.getId(), recipients.size());

        // 推入 Redis Stream，由 worker pool 並行消費
        queueService.enqueueBatch(chunkIds);

        log.info("提交推播任務：id={}, 收件人={}, 分片={}", task.getId(), recipients.size(), idx);
        return toDTO(task);
    }

    /**
     * 取消任務（將 PENDING chunk 與 task 狀態都標為 CANCELLED）。
     * 已 SENDING 中的 chunk 不會中斷，但完成後不會再處理新的。
     */
    @Transactional
    public BroadcastTaskDTO cancel(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));

        if ("COMPLETED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            throw new IllegalArgumentException("任務無法取消，目前狀態：" + task.getStatus());
        }

        task.setStatus("CANCELLED");
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);

        List<BroadcastChunk> chunks = chunkRepository.findByTaskIdOrderByChunkIndex(taskId);
        List<Long> retryIdsToRemove = new ArrayList<>();
        for (BroadcastChunk c : chunks) {
            if ("PENDING".equals(c.getStatus()) || "RETRYING".equals(c.getStatus())) {
                c.setStatus("CANCELLED");
                chunkRepository.save(c);
                retryIdsToRemove.add(c.getId());
            }
        }
        // 從 retry zset 移除（避免 scheduler 重新推入 stream）
        queueService.removeRetries(retryIdsToRemove);

        // 清除 Redis 計數鍵
        counterService.clearTask(taskId);

        log.info("已取消推播任務：id={}, 取消的 chunk 數={}", taskId, retryIdsToRemove.size());
        return toDTO(task);
    }

    /**
     * 測試發送：對單一 lineUserId 用 pushMessage，不影響任務統計。
     */
    public String testSend(Long taskId, String lineUserId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));
        List<Message> messages = parseMessages(task.getMessageContent());
        UUID retryKey = UUID.randomUUID();
        try {
            Result<?> result = messagingApiClient
                    .pushMessage(retryKey, new PushMessageRequest(lineUserId, messages, false, null))
                    .get();
            return result.requestId();
        } catch (Exception e) {
            log.error("測試推播失敗：userId={}", lineUserId, e);
            throw new IllegalArgumentException("測試推播失敗：" + e.getMessage());
        }
    }

    // ── 內部工具 ────────────────────────────────────────────────

    private void validateTargetType(String targetType) {
        if (!ALLOWED_TARGET_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("不支援的 targetType：" + targetType);
        }
    }

    private String resolveMessageContent(BroadcastCreateRequest req) {
        if (req.getTemplateId() != null) {
            MessageTemplate template = templateService.getEntityById(req.getTemplateId());
            return template.getContent();
        }
        if (req.getMessageContent() == null || req.getMessageContent().isBlank()) {
            throw new IllegalArgumentException("templateId 與 messageContent 至少需提供一項");
        }
        return req.getMessageContent();
    }

    private void validateMessageContent(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (!node.isArray() || node.isEmpty()) {
                throw new IllegalArgumentException("messageContent 必須為非空 JSON 陣列");
            }
            if (node.size() > 5) {
                throw new IllegalArgumentException("LINE 單次最多 5 則訊息");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("messageContent JSON 格式錯誤：" + e.getOriginalMessage());
        }
    }

    private String serializeTargetFilter(BroadcastCreateRequest req) {
        try {
            return switch (req.getTargetType()) {
                case "TAGS" -> objectMapper.writeValueAsString(new TargetFilter(
                        req.getTagIds(),
                        req.getTagMatch() != null ? req.getTagMatch() : "ANY",
                        null
                ));
                case "USER_LIST" -> objectMapper.writeValueAsString(new TargetFilter(
                        null, null, req.getUserIds()
                ));
                default -> null; // ALL
            };
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("targetFilter 序列化失敗", e);
        }
    }

    private void applyTargetFilterToRequest(String targetFilterJson, BroadcastCreateRequest req) {
        if (targetFilterJson == null || targetFilterJson.isBlank()) return;
        try {
            TargetFilter filter = objectMapper.readValue(targetFilterJson, TargetFilter.class);
            req.setTagIds(filter.tagIds());
            req.setTagMatch(filter.tagMatch());
            req.setUserIds(filter.userIds());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("targetFilter 反序列化失敗", e);
        }
    }

    private List<String> computeRecipients(BroadcastCreateRequest req) {
        return switch (req.getTargetType()) {
            case "ALL" -> lineUserRepository.findAllFollowedLineUserIds();
            case "TAGS" -> {
                if (req.getTagIds() == null || req.getTagIds().isEmpty()) {
                    throw new IllegalArgumentException("TAGS 目標必須提供 tagIds");
                }
                String match = req.getTagMatch() != null ? req.getTagMatch() : "ANY";
                if (!"ANY".equals(match) && !"ALL".equals(match)) {
                    throw new IllegalArgumentException("tagMatch 必須為 ANY 或 ALL");
                }
                // Phase 2 先支援 ANY；ALL（交集）改在記憶體內用 stream 處理
                if ("ALL".equals(match)) {
                    yield computeTagIntersection(req.getTagIds());
                }
                yield lineUserRepository.findLineUserIdsByTagIds(req.getTagIds());
            }
            case "USER_LIST" -> {
                if (req.getUserIds() == null || req.getUserIds().isEmpty()) {
                    throw new IllegalArgumentException("USER_LIST 目標必須提供 userIds");
                }
                yield lineUserRepository.findLineUserIdsByIds(req.getUserIds());
            }
            default -> List.of();
        };
    }

    /** 多標籤交集：找出同時擁有所有指定標籤的用戶 */
    private List<String> computeTagIntersection(List<Long> tagIds) {
        Set<String> intersection = null;
        for (Long tagId : tagIds) {
            Set<String> ids = new HashSet<>(lineUserRepository.findLineUserIdsByTagIds(List.of(tagId)));
            if (intersection == null) {
                intersection = ids;
            } else {
                intersection.retainAll(ids);
            }
            if (intersection.isEmpty()) break;
        }
        return intersection == null ? List.of() : new ArrayList<>(intersection);
    }

    private List<Message> parseMessages(String messageContent) {
        try {
            return objectMapper.readValue(messageContent, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("messages JSON 解析失敗：" + e.getOriginalMessage());
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 recipientIds 失敗", e);
        }
    }

    private BroadcastTaskDTO toDTO(BroadcastTask t) {
        BroadcastTaskDTO dto = new BroadcastTaskDTO();
        dto.setId(t.getId());
        dto.setName(t.getName());
        dto.setMessageContent(t.getMessageContent());
        dto.setTargetType(t.getTargetType());
        dto.setTargetFilter(t.getTargetFilter());
        dto.setStatus(t.getStatus());
        dto.setTotalRecipients(t.getTotalRecipients());
        dto.setSentCount(t.getSentCount());
        dto.setSuccessCount(t.getSuccessCount());
        dto.setFailedCount(t.getFailedCount());
        dto.setScheduledAt(t.getScheduledAt());
        dto.setStartedAt(t.getStartedAt());
        dto.setFinishedAt(t.getFinishedAt());
        dto.setErrorMessage(t.getErrorMessage());
        dto.setCreatedAt(t.getCreatedAt());
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }

    private BroadcastTaskDTO toDetailDTO(BroadcastTask t) {
        BroadcastTaskDTO dto = toDTO(t);
        List<BroadcastChunk> chunks = chunkRepository.findByTaskIdOrderByChunkIndex(t.getId());
        dto.setChunks(chunks.stream().map(this::toChunkSummary).toList());
        return dto;
    }

    private BroadcastTaskDTO.ChunkSummary toChunkSummary(BroadcastChunk c) {
        BroadcastTaskDTO.ChunkSummary s = new BroadcastTaskDTO.ChunkSummary();
        s.setId(c.getId());
        s.setChunkIndex(c.getChunkIndex());
        s.setStatus(c.getStatus());
        s.setAttempts(c.getAttempts());
        s.setErrorCode(c.getErrorCode());
        s.setErrorMessage(c.getErrorMessage());
        s.setSentAt(c.getSentAt());
        try {
            List<String> ids = objectMapper.readValue(c.getRecipientIds(), new TypeReference<>() {});
            s.setRecipientCount(ids.size());
        } catch (JsonProcessingException e) {
            s.setRecipientCount(0);
        }
        return s;
    }

    /** 內部序列化結構，對應 broadcast_tasks.target_filter */
    private record TargetFilter(
            List<Long> tagIds,
            String tagMatch,
            List<Long> userIds
    ) {}
}
