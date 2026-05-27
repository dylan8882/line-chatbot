package com.linechatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.service.ratelimit.LineApiRateLimiter;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.client.MessagingApiClientException;
import com.linecorp.bot.messaging.model.MulticastRequest;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BroadcastChunkProcessor 單元測試（Feature A：push 模式）。
 *
 * <p>核心情境：
 * <ul>
 *   <li>PUSH 全部成功 → status=SUCCESS, success=N, failed=0</li>
 *   <li>PUSH 混合真假 ID（部分 4xx）→ status=PARTIAL, 計數正確</li>
 *   <li>PUSH 全部 4xx → status=PARTIAL, success=0, failed=N</li>
 *   <li>PUSH 非 4xx fatal → 走 handleFailure 重試流程，計數不算錯誤那筆</li>
 *   <li>MULTICAST 模式回歸：整批當成功</li>
 * </ul>
 *
 * <p>實作備註：SDK 的 pushMessage/multicast 回傳泛型 {@code CompletableFuture<Result<XxxResponse>>}，
 * 使用 {@code thenReturn(...)} 會卡在型別推斷，改用 {@code doReturn(...)} 繞過。
 */
@ExtendWith(MockitoExtension.class)
class BroadcastChunkProcessorTest {

    @Mock BroadcastChunkRepository chunkRepository;
    @Mock BroadcastTaskRepository taskRepository;
    @Mock MessagingApiClient messagingApiClient;
    @Mock LineApiRateLimiter rateLimiter;
    @Mock BroadcastQueueService queueService;
    @Mock BroadcastCounterService counterService;

    private final ObjectMapper objectMapper = ModelObjectMapper.createNewObjectMapper();
    private BroadcastChunkProcessor processor;

    private static final String TEXT_MSG = "[{\"type\":\"text\",\"text\":\"hi\"}]";

    @BeforeEach
    void setUp() {
        processor = new BroadcastChunkProcessor(
                chunkRepository, taskRepository, messagingApiClient,
                rateLimiter, queueService, counterService, objectMapper);
        lenient().when(rateLimiter.acquirePush(anyLong())).thenReturn(true);
        lenient().when(rateLimiter.acquireMulticast(anyLong())).thenReturn(true);
        lenient().when(chunkRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(counterService.recordChunkResult(anyLong(), anyInt(), anyInt(), anyInt()))
                .thenReturn(false);
    }

    // ── PUSH 模式 ─────────────────────────────────────────────

    @Test
    @DisplayName("PUSH：3 人全部成功 → status=SUCCESS, success=3, failed=0")
    void push_allSuccess() {
        BroadcastChunk chunk = makeChunk(1L, 10L, List.of("U1", "U2", "U3"));
        BroadcastTask task = makeTask(10L, "PUSH");
        stubLookup(chunk, task);

        stubPushSequential(
                success("req-1"),
                success("req-2"),
                success("req-3"));

        boolean ok = processor.process(1L);

        assertThat(ok).isTrue();
        assertThat(chunk.getStatus()).isEqualTo("SUCCESS");
        assertThat(chunk.getLineRequestId()).isEqualTo("req-3");
        verify(messagingApiClient, times(3))
                .pushMessage(any(UUID.class), any(PushMessageRequest.class));
        verify(counterService).recordChunkResult(10L, 3, 3, 0);
    }

    @Test
    @DisplayName("PUSH：5 人混合（3 真 2 假 4xx）→ status=PARTIAL, success=3, failed=2")
    void push_partialFailure_mixedRealAndFakeIds() {
        BroadcastChunk chunk = makeChunk(2L, 20L, List.of("U1", "Ufake1", "U2", "Ufake2", "U3"));
        BroadcastTask task = makeTask(20L, "PUSH");
        stubLookup(chunk, task);

        stubPushSequential(
                success("req-1"),
                fail4xx(400),
                success("req-2"),
                fail4xx(404),
                success("req-3"));

        processor.process(2L);

        assertThat(chunk.getStatus()).isEqualTo("PARTIAL");
        assertThat(chunk.getErrorCode()).isEqualTo("PARTIAL_FAILURES");
        assertThat(chunk.getErrorMessage()).contains("2");
        verify(counterService).recordChunkResult(20L, 5, 3, 2);
        verify(queueService, never()).scheduleRetry(anyLong(), anyLong());
    }

    @Test
    @DisplayName("PUSH：全部 4xx → status=PARTIAL, success=0, failed=N, 不重試")
    void push_allFailed_4xx() {
        BroadcastChunk chunk = makeChunk(3L, 30L, List.of("Ufake1", "Ufake2"));
        BroadcastTask task = makeTask(30L, "PUSH");
        stubLookup(chunk, task);

        stubPushSequential(fail4xx(400), fail4xx(403));

        processor.process(3L);

        assertThat(chunk.getStatus()).isEqualTo("PARTIAL");
        verify(counterService).recordChunkResult(30L, 2, 0, 2);
        verify(queueService, never()).scheduleRetry(anyLong(), anyLong());
    }

    @Test
    @DisplayName("PUSH：非 4xx fatal exception → 走 handleFailure 排程重試")
    void push_fatalException_schedulesRetry() {
        BroadcastChunk chunk = makeChunk(4L, 40L, List.of("U1", "U2", "U3"));
        chunk.setMaxAttempts(4);
        BroadcastTask task = makeTask(40L, "PUSH");
        stubLookup(chunk, task);

        // 第 1 個成功 → 第 2 個 5xx fatal → 第 3 個不會被呼叫
        stubPushSequential(
                success("req-1"),
                fail5xx());

        processor.process(4L);

        verify(counterService).recordChunkResult(40L, 1, 1, 0);
        verify(queueService).scheduleRetry(eq(4L), anyLong());
        assertThat(chunk.getStatus()).isEqualTo("RETRYING");
    }

    @Test
    @DisplayName("PUSH：retry key 對每個 (chunkId, userId, attempt) 不同")
    void push_retryKey_isPerUserPerAttempt() {
        BroadcastChunk chunk = makeChunk(5L, 50L, List.of("U1", "U2"));
        BroadcastTask task = makeTask(50L, "PUSH");
        stubLookup(chunk, task);

        stubPushSequential(success("req-a"), success("req-b"));

        processor.process(5L);

        ArgumentCaptor<UUID> keyCap = ArgumentCaptor.forClass(UUID.class);
        verify(messagingApiClient, times(2))
                .pushMessage(keyCap.capture(), any(PushMessageRequest.class));
        List<UUID> keys = keyCap.getAllValues();
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    // ── MULTICAST 模式回歸 ───────────────────────────────────

    @Test
    @DisplayName("MULTICAST：一次 API call，整批當成功")
    void multicast_success() {
        BroadcastChunk chunk = makeChunk(6L, 60L, List.of("U1", "U2", "U3"));
        BroadcastTask task = makeTask(60L, "MULTICAST");
        stubLookup(chunk, task);

        doReturn(CompletableFuture.completedFuture(successResult("mc-req-1")))
                .when(messagingApiClient).multicast(any(UUID.class), any(MulticastRequest.class));

        processor.process(6L);

        assertThat(chunk.getStatus()).isEqualTo("SUCCESS");
        assertThat(chunk.getLineRequestId()).isEqualTo("mc-req-1");
        verify(messagingApiClient, never())
                .pushMessage(any(UUID.class), any(PushMessageRequest.class));
        verify(counterService).recordChunkResult(60L, 3, 3, 0);
    }

    // ── 邊界 ────────────────────────────────────────────────

    @Test
    @DisplayName("CANCELLED task：chunk 標 CANCELLED，不呼叫 API，不計數")
    void cancelledTask_skipsApiAndCounter() {
        BroadcastChunk chunk = makeChunk(7L, 70L, List.of("U1"));
        BroadcastTask task = makeTask(70L, "PUSH");
        task.setStatus("CANCELLED");
        stubLookup(chunk, task);

        processor.process(7L);

        assertThat(chunk.getStatus()).isEqualTo("CANCELLED");
        verify(messagingApiClient, never())
                .pushMessage(any(UUID.class), any(PushMessageRequest.class));
        verify(counterService, never()).recordChunkResult(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("rate limit 取 token timeout → 排程重試，attempts 不增加")
    void rateLimitTimeout_schedulesRetryWithoutIncrement() {
        BroadcastChunk chunk = makeChunk(8L, 80L, List.of("U1", "U2"));
        chunk.setAttempts(0);
        BroadcastTask task = makeTask(80L, "PUSH");
        stubLookup(chunk, task);
        when(rateLimiter.acquirePush(anyLong())).thenReturn(false);

        processor.process(8L);

        verify(queueService).scheduleRetry(eq(8L), anyLong());
        verify(messagingApiClient, never())
                .pushMessage(any(UUID.class), any(PushMessageRequest.class));
    }

    // ── 輔助 ────────────────────────────────────────────────

    /**
     * 依序為 pushMessage 設定回傳值。
     * doReturn 用 Object varargs 避免 SDK 泛型推斷問題。
     */
    private void stubPushSequential(CompletableFuture<?>... futures) {
        if (futures.length == 0) return;
        CompletableFuture<?> first = futures[0];
        CompletableFuture<?>[] rest = new CompletableFuture<?>[futures.length - 1];
        System.arraycopy(futures, 1, rest, 0, rest.length);
        doReturn(first, (Object[]) rest)
                .when(messagingApiClient).pushMessage(any(UUID.class), any(PushMessageRequest.class));
    }

    private CompletableFuture<?> success(String requestId) {
        return CompletableFuture.completedFuture(successResult(requestId));
    }

    /** 模擬 LINE SDK 拋出 4xx exception；isClient4xx 用 instanceof + getCode 判斷 */
    private CompletableFuture<?> fail4xx(int code) {
        MessagingApiClientException ex = mock(MessagingApiClientException.class);
        lenient().when(ex.getCode()).thenReturn(code);
        return CompletableFuture.failedFuture(ex);
    }

    /** 5xx（或網路錯）→ 視為 fatal，走 chunk 重試 */
    private CompletableFuture<?> fail5xx() {
        MessagingApiClientException ex = mock(MessagingApiClientException.class);
        lenient().when(ex.getCode()).thenReturn(500);
        return CompletableFuture.failedFuture(ex);
    }

    private void stubLookup(BroadcastChunk chunk, BroadcastTask task) {
        when(chunkRepository.findById(chunk.getId())).thenReturn(Optional.of(chunk));
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
    }

    private BroadcastChunk makeChunk(Long id, Long taskId, List<String> recipients) {
        try {
            return BroadcastChunk.builder()
                    .id(id)
                    .taskId(taskId)
                    .chunkIndex(0)
                    .recipientIds(objectMapper.writeValueAsString(recipients))
                    .status("PENDING")
                    .attempts(0)
                    .maxAttempts(4)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BroadcastTask makeTask(Long id, String apiMode) {
        return BroadcastTask.builder()
                .id(id)
                .name("test")
                .targetType("ALL")
                .messageContent(TEXT_MSG)
                .apiMode(apiMode)
                .status("RUNNING")
                .totalRecipients(0)
                .sentCount(0)
                .successCount(0)
                .failedCount(0)
                .build();
    }

    private Result<?> successResult(String requestId) {
        Result<?> r = mock(Result.class);
        lenient().when(r.requestId()).thenReturn(requestId);
        return r;
    }
}
