package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.config.BroadcastConfig;
import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.AbTestComparisonDTO;
import com.linechatbot.model.dto.AbTestCreateRequest;
import com.linechatbot.model.dto.BroadcastCreateRequest;
import com.linechatbot.model.dto.BroadcastEstimateResponse;
import com.linechatbot.model.dto.BroadcastProgressEvent;
import com.linechatbot.model.dto.BroadcastTaskDTO;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.model.entity.MessageTemplate;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.ClickEventRepository;
import com.linechatbot.repository.LineUserRepository;
import com.linechatbot.security.CurrentUserService;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.NarrowcastRequest;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
    private final BroadcastProgressService progressService;
    private final ClickLinkRewriter clickLinkRewriter;
    private final ClickEventRepository clickEventRepository;
    private final MessagingApiClient messagingApiClient;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final com.linechatbot.service.ratelimit.LineApiRateLimiter rateLimiter;

    private static final Set<String> ALLOWED_TARGET_TYPES = Set.of("ALL", "TAGS", "USER_LIST", "NARROWCAST");

    public Page<BroadcastTaskDTO> list(String status, Pageable pageable) {
        String st = (status != null && !status.isBlank()) ? status : null;
        return taskRepository.search(st, pageable).map(this::toDTO);
    }

    /** 帶上 chunks 摘要的 detail。 */
    public BroadcastTaskDTO getDetail(Long id) {
        BroadcastTask task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", id));
        return toDetailDTO(task);
    }

    /** 預估收件人數，不寫入任何資料。chunk 數依 apiMode 計算。 */
    public BroadcastEstimateResponse estimate(BroadcastCreateRequest req) {
        List<String> recipients = computeRecipients(req);
        int chunkSize = computeChunkSize(resolveApiMode(req), recipients.size());
        int chunks = recipients.isEmpty() ? 0 : (int) Math.ceil(recipients.size() / (double) chunkSize);
        return new BroadcastEstimateResponse(recipients.size(), chunks);
    }

    /** DRAFT 狀態建立；相同 idempotencyKey 視為重送、回傳既有任務。 */
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

        // scheduledAt 在未來 → SCHEDULED，到時 BroadcastScheduler 會替你呼叫 submit
        String initialStatus = "DRAFT";
        if (req.getScheduledAt() != null && req.getScheduledAt().isAfter(LocalDateTime.now())) {
            initialStatus = "SCHEDULED";
        }

        BroadcastTask task = BroadcastTask.builder()
                .name(req.getName())
                .messageContent(messageContent)
                .targetType(req.getTargetType())
                .targetFilter(serializeTargetFilter(req))
                .apiMode(resolveApiMode(req))
                .scheduledAt(req.getScheduledAt())
                .idempotencyKey(req.getIdempotencyKey())
                .status(initialStatus)
                .createdBy(currentUserService.getCurrentUser().orElse(null))
                .build();

        BroadcastTask saved = taskRepository.save(task);
        log.info("建立推播任務：id={}, name={}, targetType={}, status={}",
                saved.getId(), saved.getName(), saved.getTargetType(), saved.getStatus());
        return toDTO(saved);
    }

    /** 計算收件人、切片、入 Redis Stream 給 worker 派發。 */
    @Transactional
    public BroadcastTaskDTO submit(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("BroadcastTask", taskId));

        if (!"DRAFT".equals(task.getStatus()) && !"QUEUED".equals(task.getStatus())
                && !"SCHEDULED".equals(task.getStatus())) {
            throw new IllegalArgumentException("任務狀態無法提交：" + task.getStatus());
        }

        // 送出前把 message JSON 內的外部 URL 改寫成 tracking link
        String rewritten = clickLinkRewriter.rewriteForTask(task.getId(), task.getMessageContent());
        if (!rewritten.equals(task.getMessageContent())) {
            task.setMessageContent(rewritten);
            taskRepository.save(task);
        }

        // NARROWCAST 走 LINE 平台自有的大規模分發，不需自管 chunks
        if ("NARROWCAST".equals(task.getTargetType())) {
            return submitNarrowcast(task);
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

        int chunkSize = computeChunkSize(task.getApiMode(), recipients.size());
        int idx = 0;
        List<Long> chunkIds = new ArrayList<>();
        for (int i = 0; i < recipients.size(); i += chunkSize) {
            List<String> slice = recipients.subList(i, Math.min(i + chunkSize, recipients.size()));
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

        // 推入 Redis Stream 必須延後到 transaction commit 之後，
        // 否則 worker 撈到 chunk id 後到 DB 找不到（race condition）。
        final List<Long> idsToEnqueue = List.copyOf(chunkIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queueService.enqueueBatch(idsToEnqueue);
            }
        });

        log.info("提交推播任務：id={}, 收件人={}, 分片={}", task.getId(), recipients.size(), idx);
        return toDTO(task);
    }

    /** SENDING 中的 chunk 不會被中斷，但完成後不會再撈新的；PENDING / RETRYING 改 CANCELLED。 */
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

        // 廣播取消事件給 SSE 訂閱者（要在 clearTask 之前，否則 Redis 計數已清除）
        progressService.publish(BroadcastProgressEvent.builder()
                .type("CANCELLED")
                .taskId(taskId)
                .status("CANCELLED")
                .sentCount(task.getSentCount())
                .successCount(task.getSuccessCount())
                .failedCount(task.getFailedCount())
                .totalRecipients(task.getTotalRecipients())
                .build());

        counterService.clearTask(taskId);

        log.info("已取消推播任務：id={}, 取消的 chunk 數={}", taskId, retryIdsToRemove.size());
        return toDTO(task);
    }

    /**
     * 按 variants 切 audience、建立 N 個獨立 task（同 abTestId、不同 variantLabel），
     * 分配採隨機 shuffle 後依 trafficPercent 切片；caller 自行 submit 個別 variant。
     */
    @Transactional
    public List<BroadcastTaskDTO> createAbTest(AbTestCreateRequest req) {
        if (req.getVariants().stream().mapToInt(AbTestCreateRequest.Variant::getTrafficPercent).sum() != 100) {
            throw new IllegalArgumentException("variants 的 trafficPercent 加總必須為 100");
        }
        if ("NARROWCAST".equals(req.getTargetType())) {
            throw new IllegalArgumentException("NARROWCAST 不支援 A/B 測試");
        }

        BroadcastCreateRequest audReq = new BroadcastCreateRequest();
        audReq.setTargetType(req.getTargetType());
        audReq.setTagIds(req.getTagIds());
        audReq.setTagMatch(req.getTagMatch());
        audReq.setUserIds(req.getUserIds());
        List<String> recipients = new ArrayList<>(computeRecipients(audReq));
        Collections.shuffle(recipients);

        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("沒有符合條件的收件人");
        }

        String abTestId = UUID.randomUUID().toString();
        List<BroadcastTaskDTO> created = new ArrayList<>();
        int total = recipients.size();
        int offset = 0;
        for (int i = 0; i < req.getVariants().size(); i++) {
            AbTestCreateRequest.Variant v = req.getVariants().get(i);
            int take = (i == req.getVariants().size() - 1)
                    ? total - offset // 最後一個取剩下全部，避免 rounding 漏算
                    : (int) Math.round(total * (v.getTrafficPercent() / 100.0));
            List<String> sliceLineUserIds = recipients.subList(offset, offset + take);
            List<Long> sliceUserIds = sliceLineUserIds.isEmpty()
                    ? List.of()
                    : lineUserRepository.findIdsByLineUserIds(sliceLineUserIds);
            offset += take;

            // variant 用 USER_LIST target 重用既有派發邏輯，不另開新的 entity / flow
            BroadcastCreateRequest variantReq = new BroadcastCreateRequest();
            variantReq.setName(req.getName() + " [" + v.getLabel() + "]");
            variantReq.setTemplateId(v.getTemplateId());
            variantReq.setMessageContent(v.getMessageContent());
            variantReq.setTargetType("USER_LIST");
            variantReq.setUserIds(sliceUserIds);
            variantReq.setApiMode(req.getApiMode());
            variantReq.setScheduledAt(req.getScheduledAt());
            variantReq.setIdempotencyKey(req.getIdempotencyKey() == null
                    ? null : req.getIdempotencyKey() + "-" + v.getLabel());

            BroadcastTaskDTO dto = create(variantReq);
            BroadcastTask t = taskRepository.findById(dto.getId()).orElseThrow();
            t.setAbTestId(abTestId);
            t.setVariantLabel(v.getLabel());
            taskRepository.save(t);
            dto = toDTO(t);
            created.add(dto);
        }
        log.info("建立 A/B 測試：abTestId={}, variants={}, 總收件人={}",
                abTestId, created.size(), recipients.size());
        return created;
    }

    public AbTestComparisonDTO getAbTestComparison(String abTestId) {
        List<BroadcastTask> tasks = taskRepository.findByAbTestIdOrderByVariantLabel(abTestId);
        if (tasks.isEmpty()) {
            throw new ResourceNotFoundException("AbTest " + abTestId + " 不存在");
        }
        AbTestComparisonDTO dto = new AbTestComparisonDTO();
        dto.setAbTestId(abTestId);
        // 取掉 "[A]" 等後綴作為共同名稱
        String firstName = tasks.get(0).getName();
        int bracket = firstName.lastIndexOf(" [");
        dto.setTaskName(bracket > 0 ? firstName.substring(0, bracket) : firstName);

        dto.setVariants(tasks.stream().map(t -> {
            AbTestComparisonDTO.VariantStat v = new AbTestComparisonDTO.VariantStat();
            v.setTaskId(t.getId());
            v.setLabel(t.getVariantLabel());
            v.setStatus(t.getStatus());
            v.setTotalRecipients(t.getTotalRecipients() == null ? 0 : t.getTotalRecipients());
            v.setSentCount(t.getSentCount() == null ? 0 : t.getSentCount());
            v.setSuccessCount(t.getSuccessCount() == null ? 0 : t.getSuccessCount());
            v.setFailedCount(t.getFailedCount() == null ? 0 : t.getFailedCount());
            int denom = v.getSuccessCount() + v.getFailedCount();
            v.setSuccessRate(denom == 0 ? 0 : (v.getSuccessCount() * 1.0) / denom);
            // 點擊率才是 A/B 真正要比的（送達率對 multicast 沒意義）
            long clicks = clickEventRepository.countByTaskId(t.getId());
            v.setTotalClicks(clicks);
            v.setClickRate(v.getSuccessCount() == 0 ? 0 : (clicks * 1.0) / v.getSuccessCount());
            return v;
        }).toList());
        return dto;
    }

    /** 不自管 chunks，requestId 存進 task、由 BroadcastNarrowcastPoller 輪詢進度。 */
    private BroadcastTaskDTO submitNarrowcast(BroadcastTask task) {
        List<Message> messages = parseMessages(task.getMessageContent());
        UUID retryKey = UUID.nameUUIDFromBytes(("narrowcast-" + task.getId()).getBytes());
        try {
            Result<?> result = messagingApiClient
                    .narrowcast(retryKey, new NarrowcastRequest(messages, null, null, null, false))
                    .get();
            task.setNarrowcastRequestId(result.requestId());
            task.setStatus("RUNNING");
            task.setStartedAt(LocalDateTime.now());
            taskRepository.save(task);
            log.info("NARROWCAST 已提交：taskId={}, requestId={}", task.getId(), result.requestId());
            return toDTO(task);
        } catch (Exception e) {
            log.error("NARROWCAST 提交失敗：taskId={}", task.getId(), e);
            task.setStatus("FAILED");
            task.setErrorMessage("Narrowcast 提交失敗：" + e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskRepository.save(task);
            throw new IllegalStateException("Narrowcast 提交失敗：" + e.getMessage(), e);
        }
    }

    /** 對單一 lineUserId 用 pushMessage，不寫入 chunk、不影響任務統計。 */
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

    /**
     * 決定切片大小。
     * <p>MULTICAST 用 LINE 硬上限 500 人/批；PUSH 依當前 rate limit 換算
     * 「目標每 chunk 處理時間」決定（rate × {@link BroadcastConfig#PUSH_CHUNK_TARGET_SECONDS} 秒），
     * 取 [{@link BroadcastConfig#PUSH_CHUNK_MIN}, {@link BroadcastConfig#PUSH_CHUNK_MAX}] 邊界。
     * <p>這樣 worker 鎖一個 chunk 的時間 ≈ 2 秒，跟 rate config 升降連動。
     */
    int computeChunkSize(String apiMode, int totalRecipients) {
        if ("MULTICAST".equals(apiMode)) {
            return BroadcastConfig.CHUNK_SIZE;
        }
        // PUSH：rate × 目標秒數，再夾在邊界與「不超過總人數」之間
        int target = (int) Math.ceil(rateLimiter.getPushRefillPerSecond()
                * BroadcastConfig.PUSH_CHUNK_TARGET_SECONDS);
        int bounded = Math.min(Math.max(target, BroadcastConfig.PUSH_CHUNK_MIN),
                BroadcastConfig.PUSH_CHUNK_MAX);
        return Math.min(bounded, Math.max(totalRecipients, 1));
    }

    private void validateTargetType(String targetType) {
        if (!ALLOWED_TARGET_TYPES.contains(targetType)) {
            throw new IllegalArgumentException("不支援的 targetType：" + targetType);
        }
    }

    /**
     * 前端指定為主、沒指定預設 PUSH；NARROWCAST 強制設為 MULTICAST
     * （narrowcast 實際不用這欄位，只是讓 DB 看起來一致）。
     */
    private String resolveApiMode(BroadcastCreateRequest req) {
        if ("NARROWCAST".equals(req.getTargetType())) {
            return "MULTICAST";
        }
        String mode = req.getApiMode();
        if (mode == null || mode.isBlank()) {
            return "PUSH";
        }
        if (!"PUSH".equals(mode) && !"MULTICAST".equals(mode)) {
            throw new IllegalArgumentException("不支援的 apiMode：" + mode);
        }
        return mode;
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
                // ALL（交集）在記憶體做、不寫複雜的 native query 拖長 DB 鎖時間
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
        dto.setApiMode(t.getApiMode());
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
        dto.setAbTestId(t.getAbTestId());
        dto.setVariantLabel(t.getVariantLabel());
        dto.setNarrowcastRequestId(t.getNarrowcastRequestId());
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
