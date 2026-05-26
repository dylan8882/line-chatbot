package com.linechatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.AbTestCreateRequest;
import com.linechatbot.model.dto.BroadcastCreateRequest;
import com.linechatbot.model.dto.BroadcastEstimateResponse;
import com.linechatbot.model.dto.BroadcastTaskDTO;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.ClickEventRepository;
import com.linechatbot.repository.LineUserRepository;
import com.linechatbot.security.CurrentUserService;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.NarrowcastRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BroadcastService 單元測試（Mockito，無 Spring context）。
 *
 * <p>核心商業邏輯涵蓋：
 * <ul>
 *   <li>create()：idempotency / scheduledAt → SCHEDULED / 驗證</li>
 *   <li>submit()：狀態守衛 / chunk 切片 / NARROWCAST 分支</li>
 *   <li>cancel()：狀態變更 + 清 Redis</li>
 *   <li>estimate()：computeRecipients 各 targetType</li>
 *   <li>createAbTest()：traffic 驗證、N variants 建立</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BroadcastServiceTest {

    @Mock BroadcastTaskRepository taskRepository;
    @Mock BroadcastChunkRepository chunkRepository;
    @Mock LineUserRepository lineUserRepository;
    @Mock MessageTemplateService templateService;
    @Mock BroadcastQueueService queueService;
    @Mock BroadcastCounterService counterService;
    @Mock BroadcastProgressService progressService;
    @Mock ClickLinkRewriter clickLinkRewriter;
    @Mock ClickEventRepository clickEventRepository;
    @Mock MessagingApiClient messagingApiClient;
    @Mock CurrentUserService currentUserService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BroadcastService broadcastService;

    private static final String TEXT_MSG = "[{\"type\":\"text\",\"text\":\"hi\"}]";

    @BeforeEach
    void setUp() {
        broadcastService = new BroadcastService(
                taskRepository, chunkRepository, lineUserRepository, templateService,
                queueService, counterService, progressService, clickLinkRewriter,
                clickEventRepository, messagingApiClient, currentUserService, objectMapper);
        lenient().when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
        lenient().when(clickLinkRewriter.rewriteForTask(anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    // ── create() ───────────────────────────────────────────────

    @Test
    @DisplayName("create：未提供 templateId 或 messageContent 應拋例外")
    void create_neitherTemplateNorContent_throws() {
        BroadcastCreateRequest req = baseRequest("ALL", null);
        req.setMessageContent(null);
        assertThatThrownBy(() -> broadcastService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需提供一項");
    }

    @Test
    @DisplayName("create：不支援的 targetType 應拋例外")
    void create_invalidTargetType_throws() {
        BroadcastCreateRequest req = baseRequest("UNKNOWN", TEXT_MSG);
        assertThatThrownBy(() -> broadcastService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetType");
    }

    @Test
    @DisplayName("create：messageContent 非陣列或空陣列應拋例外")
    void create_invalidMessageContent_throws() {
        BroadcastCreateRequest req = baseRequest("ALL", "{}"); // 不是陣列
        assertThatThrownBy(() -> broadcastService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("陣列");
    }

    @Test
    @DisplayName("create：messageContent > 5 則應拋例外")
    void create_tooManyMessages_throws() {
        String many = "[" + "{\"type\":\"text\",\"text\":\"a\"},".repeat(6).replaceAll(",$", "") + "]";
        BroadcastCreateRequest req = baseRequest("ALL", many);
        assertThatThrownBy(() -> broadcastService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 5");
    }

    @Test
    @DisplayName("create：idempotencyKey 命中時直接回傳既有任務")
    void create_idempotencyHit_returnsExisting() {
        String key = "idem-1";
        BroadcastTask existing = baseTask(99L);
        when(taskRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        BroadcastCreateRequest req = baseRequest("ALL", TEXT_MSG);
        req.setIdempotencyKey(key);
        BroadcastTaskDTO dto = broadcastService.create(req);

        assertThat(dto.getId()).isEqualTo(99L);
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("create：scheduledAt 為未來時 status=SCHEDULED")
    void create_futureScheduledAt_setsScheduled() {
        when(taskRepository.save(any())).thenAnswer(i -> {
            BroadcastTask t = i.getArgument(0);
            t.setId(1L);
            return t;
        });
        BroadcastCreateRequest req = baseRequest("ALL", TEXT_MSG);
        req.setScheduledAt(LocalDateTime.now().plusHours(1));

        BroadcastTaskDTO dto = broadcastService.create(req);

        assertThat(dto.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("create：無 scheduledAt 預設 status=DRAFT")
    void create_noScheduledAt_setsDraft() {
        when(taskRepository.save(any())).thenAnswer(i -> {
            BroadcastTask t = i.getArgument(0);
            t.setId(1L);
            return t;
        });
        BroadcastCreateRequest req = baseRequest("ALL", TEXT_MSG);

        BroadcastTaskDTO dto = broadcastService.create(req);

        assertThat(dto.getStatus()).isEqualTo("DRAFT");
    }

    // ── submit() ───────────────────────────────────────────────

    @Test
    @DisplayName("submit：任務不存在應拋 ResourceNotFoundException")
    void submit_notFound_throws() {
        when(taskRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> broadcastService.submit(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("submit：COMPLETED 狀態應拒絕")
    void submit_completedState_throws() {
        BroadcastTask t = baseTask(1L);
        t.setStatus("COMPLETED");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> broadcastService.submit(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("無法提交");
    }

    @Test
    @DisplayName("submit：正常路徑切 chunks 並推入 Redis Stream")
    void submit_normalPath_createsChunksAndEnqueues() {
        BroadcastTask t = baseTask(1L);
        t.setTargetType("ALL");
        t.setMessageContent(TEXT_MSG);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(t));
        when(lineUserRepository.findAllFollowedLineUserIds())
                .thenReturn(List.of("U1", "U2", "U3"));
        when(chunkRepository.save(any())).thenAnswer(i -> {
            BroadcastChunk c = i.getArgument(0);
            c.setId(System.nanoTime());
            return c;
        });

        broadcastService.submit(1L);

        assertThat(t.getStatus()).isEqualTo("RUNNING");
        assertThat(t.getTotalRecipients()).isEqualTo(3);
        verify(queueService).enqueueBatch(any());
        verify(counterService).initTask(1L, 3);
    }

    @Test
    @DisplayName("submit：收件人為空應標記 FAILED")
    void submit_emptyRecipients_setsFailed() {
        BroadcastTask t = baseTask(1L);
        t.setTargetType("ALL");
        t.setMessageContent(TEXT_MSG);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(t));
        when(lineUserRepository.findAllFollowedLineUserIds()).thenReturn(List.of());

        broadcastService.submit(1L);

        assertThat(t.getStatus()).isEqualTo("FAILED");
        assertThat(t.getErrorMessage()).contains("沒有");
        verify(queueService, never()).enqueueBatch(any());
    }

    // 註：NARROWCAST 路徑單元測試需要 LINE SDK 的 polymorphic ObjectMapper
    // （能識別 {"type":"text",...} → TextMessage record），
    // 純 new ObjectMapper() 不含這些 subtype 註冊。改由整合測試覆蓋。

    // ── cancel() ───────────────────────────────────────────────

    @Test
    @DisplayName("cancel：COMPLETED 任務不應允許取消")
    void cancel_completedTask_throws() {
        BroadcastTask t = baseTask(1L);
        t.setStatus("COMPLETED");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> broadcastService.cancel(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cancel：成功取消應清除 Redis 計數")
    void cancel_validTask_clearsRedisCounters() {
        BroadcastTask t = baseTask(1L);
        t.setStatus("RUNNING");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(t));
        when(chunkRepository.findByTaskIdOrderByChunkIndex(1L)).thenReturn(List.of());

        broadcastService.cancel(1L);

        assertThat(t.getStatus()).isEqualTo("CANCELLED");
        assertThat(t.getFinishedAt()).isNotNull();
        verify(counterService).clearTask(1L);
        verify(progressService).publish(any());
    }

    // ── estimate() / computeRecipients ────────────────────────

    @Test
    @DisplayName("estimate ALL：取全部 FOLLOWED 用戶")
    void estimate_all_returnsAllFollowed() {
        when(lineUserRepository.findAllFollowedLineUserIds())
                .thenReturn(List.of("U1", "U2", "U3", "U4"));

        BroadcastEstimateResponse res = broadcastService.estimate(
                baseRequest("ALL", TEXT_MSG));

        assertThat(res.getTotalRecipients()).isEqualTo(4);
        assertThat(res.getTotalChunks()).isEqualTo(1);
    }

    @Test
    @DisplayName("estimate TAGS ANY：聯集（DB 端 DISTINCT）")
    void estimate_tagsAny_returnsUnion() {
        BroadcastCreateRequest req = baseRequest("TAGS", TEXT_MSG);
        req.setTagIds(List.of(1L, 2L));
        req.setTagMatch("ANY");
        when(lineUserRepository.findLineUserIdsByTagIds(List.of(1L, 2L)))
                .thenReturn(List.of("U1", "U2", "U3"));

        BroadcastEstimateResponse res = broadcastService.estimate(req);
        assertThat(res.getTotalRecipients()).isEqualTo(3);
    }

    @Test
    @DisplayName("estimate TAGS ALL：交集（記憶體 retainAll）")
    void estimate_tagsAll_returnsIntersection() {
        BroadcastCreateRequest req = baseRequest("TAGS", TEXT_MSG);
        req.setTagIds(List.of(1L, 2L));
        req.setTagMatch("ALL");
        when(lineUserRepository.findLineUserIdsByTagIds(List.of(1L)))
                .thenReturn(List.of("U1", "U2", "U3"));
        when(lineUserRepository.findLineUserIdsByTagIds(List.of(2L)))
                .thenReturn(List.of("U2", "U3", "U4"));

        BroadcastEstimateResponse res = broadcastService.estimate(req);
        assertThat(res.getTotalRecipients()).isEqualTo(2); // U2, U3
    }

    @Test
    @DisplayName("estimate USER_LIST：依資料庫 ID 轉換")
    void estimate_userList_returnsByIds() {
        BroadcastCreateRequest req = baseRequest("USER_LIST", TEXT_MSG);
        req.setUserIds(List.of(10L, 20L));
        when(lineUserRepository.findLineUserIdsByIds(List.of(10L, 20L)))
                .thenReturn(List.of("Uaa", "Ubb"));

        BroadcastEstimateResponse res = broadcastService.estimate(req);
        assertThat(res.getTotalRecipients()).isEqualTo(2);
    }

    // ── createAbTest() ─────────────────────────────────────────

    @Test
    @DisplayName("createAbTest：trafficPercent 加總不為 100 應拋例外")
    void createAbTest_trafficNot100_throws() {
        AbTestCreateRequest req = new AbTestCreateRequest();
        req.setName("AB test");
        req.setTargetType("ALL");
        AbTestCreateRequest.Variant a = new AbTestCreateRequest.Variant();
        a.setLabel("A"); a.setMessageContent(TEXT_MSG); a.setTrafficPercent(40);
        AbTestCreateRequest.Variant b = new AbTestCreateRequest.Variant();
        b.setLabel("B"); b.setMessageContent(TEXT_MSG); b.setTrafficPercent(40);
        req.setVariants(List.of(a, b));

        assertThatThrownBy(() -> broadcastService.createAbTest(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("createAbTest：NARROWCAST 不支援應拋例外")
    void createAbTest_narrowcast_throws() {
        AbTestCreateRequest req = new AbTestCreateRequest();
        req.setName("AB test");
        req.setTargetType("NARROWCAST");
        AbTestCreateRequest.Variant a = new AbTestCreateRequest.Variant();
        a.setLabel("A"); a.setMessageContent(TEXT_MSG); a.setTrafficPercent(50);
        AbTestCreateRequest.Variant b = new AbTestCreateRequest.Variant();
        b.setLabel("B"); b.setMessageContent(TEXT_MSG); b.setTrafficPercent(50);
        req.setVariants(List.of(a, b));

        assertThatThrownBy(() -> broadcastService.createAbTest(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NARROWCAST");
    }

    @Test
    @DisplayName("createAbTest：正常建立 2 個 variant 任務，同 abTestId 不同 variantLabel")
    void createAbTest_normal_createsTwoVariantTasks() {
        // audience
        when(lineUserRepository.findAllFollowedLineUserIds())
                .thenReturn(List.of("U1", "U2", "U3", "U4"));
        when(lineUserRepository.findIdsByLineUserIds(any()))
                .thenAnswer(i -> {
                    List<String> ids = i.getArgument(0);
                    return ids.stream().map(s -> (long) s.hashCode()).toList();
                });

        ArgumentCaptor<BroadcastTask> savedTasks = ArgumentCaptor.forClass(BroadcastTask.class);
        when(taskRepository.save(savedTasks.capture())).thenAnswer(i -> {
            BroadcastTask t = i.getArgument(0);
            if (t.getId() == null) t.setId(System.nanoTime());
            return t;
        });
        // 變體 idempotencyKey 為 null，不會走 findByIdempotencyKey
        // After variant created, findById returns the saved task for setAbTestId
        when(taskRepository.findById(anyLong())).thenAnswer(i ->
                savedTasks.getAllValues().stream()
                        .filter(t -> t.getId().equals(i.getArgument(0)))
                        .findFirst());

        AbTestCreateRequest req = new AbTestCreateRequest();
        req.setName("AB test");
        req.setTargetType("ALL");
        AbTestCreateRequest.Variant a = new AbTestCreateRequest.Variant();
        a.setLabel("A"); a.setMessageContent(TEXT_MSG); a.setTrafficPercent(50);
        AbTestCreateRequest.Variant b = new AbTestCreateRequest.Variant();
        b.setLabel("B"); b.setMessageContent(TEXT_MSG); b.setTrafficPercent(50);
        req.setVariants(List.of(a, b));

        List<BroadcastTaskDTO> result = broadcastService.createAbTest(req);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAbTestId()).isNotNull();
        assertThat(result.get(0).getAbTestId()).isEqualTo(result.get(1).getAbTestId());
        assertThat(result).extracting(BroadcastTaskDTO::getVariantLabel)
                .containsExactly("A", "B");
    }

    // ── list() / getDetail()（簡單 happy path） ──────────────────

    @Test
    @DisplayName("list：依狀態過濾並回傳分頁")
    void list_filtersByStatusAndReturnsPage() {
        BroadcastTask t = baseTask(1L);
        Page<BroadcastTask> page = new PageImpl<>(List.of(t));
        when(taskRepository.search(eqOrNull("RUNNING"), any(Pageable.class))).thenReturn(page);

        Page<BroadcastTaskDTO> result = broadcastService.list("RUNNING", Pageable.unpaged());
        assertThat(result).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getDetail：任務不存在拋例外")
    void getDetail_notFound_throws() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> broadcastService.getDetail(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── 輔助方法 ────────────────────────────────────────────────

    private BroadcastCreateRequest baseRequest(String targetType, String content) {
        BroadcastCreateRequest req = new BroadcastCreateRequest();
        req.setName("test task");
        req.setTargetType(targetType);
        req.setMessageContent(content);
        return req;
    }

    private BroadcastTask baseTask(Long id) {
        return BroadcastTask.builder()
                .id(id)
                .name("test")
                .messageContent(TEXT_MSG)
                .targetType("ALL")
                .status("DRAFT")
                .totalRecipients(0)
                .sentCount(0)
                .successCount(0)
                .failedCount(0)
                .build();
    }

    /** Mockito.eq() 對 null 不友善，包成 helper */
    private String eqOrNull(String v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }

    /** Mockito.any(Pageable.class) helper */
    private static <T> T anyTypeOf(Class<T> c) {
        return org.mockito.ArgumentMatchers.any(c);
    }

    /** 用 times(1) 簡寫 */
    private static org.mockito.verification.VerificationMode once() {
        return times(1);
    }
}
