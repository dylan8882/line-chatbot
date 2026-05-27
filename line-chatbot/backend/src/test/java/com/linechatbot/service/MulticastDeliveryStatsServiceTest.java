package com.linechatbot.service;

import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.model.entity.MulticastDailyDelivery;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.MulticastDailyDeliveryRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.NumberOfMessagesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MulticastDeliveryStatsService 單元測試。
 *
 * <p>核心情境：
 * <ul>
 *   <li>當日第一個 task：delta = LINE total（last_total 從 0 起算）</li>
 *   <li>當日第二個 task：delta = LINE total − 上次 last_total</li>
 *   <li>LINE API 尚未 ready（status=UNREADY/OUT_OF_SERVICE 等）→ 跳過、不寫入</li>
 *   <li>已結算過的 task 不重複處理（race condition）</li>
 *   <li>LINE total 變小（不該發生但 defensive）→ delta = 0 不是負數</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MulticastDeliveryStatsServiceTest {

    @Mock BroadcastTaskRepository taskRepository;
    @Mock MulticastDailyDeliveryRepository dailyRepository;
    @Mock MessagingApiClient messagingApiClient;

    private MulticastDeliveryStatsService service;

    @BeforeEach
    void setUp() {
        service = new MulticastDeliveryStatsService(taskRepository, dailyRepository, messagingApiClient);
        lenient().when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(dailyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("第一個 task：delta = LINE total，新建 MulticastDailyDelivery")
    void settleOne_firstTaskOfDay_deltaEqualsTotal() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        BroadcastTask task = makeTask(1L, today.atTime(10, 0));
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(dailyRepository.findById(today)).thenReturn(Optional.empty());
        stubLineDeliveryReady(150L);

        service.settleOne(1L);

        assertThat(task.getLineDeliveredDelta()).isEqualTo(150L);
        ArgumentCaptor<MulticastDailyDelivery> dailyCap = ArgumentCaptor.forClass(MulticastDailyDelivery.class);
        verify(dailyRepository).save(dailyCap.capture());
        assertThat(dailyCap.getValue().getDate()).isEqualTo(today);
        assertThat(dailyCap.getValue().getLastTotal()).isEqualTo(150L);
    }

    @Test
    @DisplayName("第二個 task：delta = total − last_total，更新 cache")
    void settleOne_secondTaskOfDay_deltaIsDifference() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        BroadcastTask task = makeTask(2L, today.atTime(11, 30));
        when(taskRepository.findById(2L)).thenReturn(Optional.of(task));
        MulticastDailyDelivery existing = MulticastDailyDelivery.builder()
                .date(today).lastTotal(150L).build();
        when(dailyRepository.findById(today)).thenReturn(Optional.of(existing));
        stubLineDeliveryReady(250L);

        service.settleOne(2L);

        assertThat(task.getLineDeliveredDelta()).isEqualTo(100L);
        assertThat(existing.getLastTotal()).isEqualTo(250L);
        verify(dailyRepository).save(existing);
    }

    @Test
    @DisplayName("LINE API 尚未 ready：跳過、不寫入任何資料")
    void settleOne_lineNotReady_skips() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        BroadcastTask task = makeTask(3L, today.atTime(12, 0));
        when(taskRepository.findById(3L)).thenReturn(Optional.of(task));
        stubLineDelivery(NumberOfMessagesResponse.Status.UNREADY, null);

        service.settleOne(3L);

        assertThat(task.getLineDeliveredDelta()).isNull();
        verify(taskRepository, never()).save(any());
        verify(dailyRepository, never()).save(any());
    }

    @Test
    @DisplayName("已結算過：直接 return 不重複處理")
    void settleOne_alreadySettled_noop() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        BroadcastTask task = makeTask(4L, today.atTime(12, 0));
        task.setLineDeliveredDelta(42L);
        when(taskRepository.findById(4L)).thenReturn(Optional.of(task));

        service.settleOne(4L);

        verify(messagingApiClient, never()).getNumberOfSentMulticastMessages(anyString());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("total 比 last_total 還小：delta 被夾到 0，不是負數")
    void settleOne_totalDecreased_deltaClampedToZero() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        BroadcastTask task = makeTask(5L, today.atTime(12, 0));
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        MulticastDailyDelivery existing = MulticastDailyDelivery.builder()
                .date(today).lastTotal(500L).build();
        when(dailyRepository.findById(today)).thenReturn(Optional.of(existing));
        stubLineDeliveryReady(450L); // 比 last_total 小

        service.settleOne(5L);

        assertThat(task.getLineDeliveredDelta()).isEqualTo(0L);
        assertThat(existing.getLastTotal()).isEqualTo(450L);
    }

    @Test
    @DisplayName("LINE API 拋例外：吞掉、不寫入 task（下輪 scheduler 會重試）")
    void settleOne_lineApiThrows_swallowsAndSkips() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        BroadcastTask task = makeTask(6L, today.atTime(12, 0));
        when(taskRepository.findById(6L)).thenReturn(Optional.of(task));
        doReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")))
                .when(messagingApiClient).getNumberOfSentMulticastMessages(anyString());

        service.settleOne(6L);

        assertThat(task.getLineDeliveredDelta()).isNull();
        verify(taskRepository, never()).save(any());
        verify(dailyRepository, never()).save(any());
    }

    // ── 輔助 ────────────────────────────────────────────────

    private BroadcastTask makeTask(Long id, LocalDateTime finishedAt) {
        return BroadcastTask.builder()
                .id(id)
                .name("multicast task")
                .targetType("ALL")
                .messageContent("[]")
                .apiMode("MULTICAST")
                .status("COMPLETED")
                .totalRecipients(100)
                .sentCount(100)
                .successCount(100)
                .failedCount(0)
                .finishedAt(finishedAt)
                .build();
    }

    private void stubLineDeliveryReady(long total) {
        stubLineDelivery(NumberOfMessagesResponse.Status.READY, total);
    }

    private void stubLineDelivery(NumberOfMessagesResponse.Status status, Long total) {
        NumberOfMessagesResponse body = new NumberOfMessagesResponse(status, total);
        Result<?> result = mock(Result.class);
        lenient().when(result.body()).thenReturn(body);
        doReturn(CompletableFuture.completedFuture(result))
                .when(messagingApiClient).getNumberOfSentMulticastMessages(anyString());
    }
}
