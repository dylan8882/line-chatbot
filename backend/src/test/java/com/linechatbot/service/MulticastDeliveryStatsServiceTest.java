package com.linechatbot.service;

import com.linechatbot.model.entity.MulticastDailyDelivery;
import com.linechatbot.repository.MulticastDailyDeliveryRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.NumberOfMessagesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * <p>新設計：不再做 per-task delta。service 提供 daily total 查詢（帶 5 分鐘快取）。
 *
 * <p>覆蓋情境：
 * <ul>
 *   <li>快取命中（≤ 5 min）→ 不打 LINE，回快取</li>
 *   <li>快取過期 → 打 LINE → READY → 更新快取</li>
 *   <li>快取過期 → 打 LINE → UNREADY → 不寫快取，回舊快取</li>
 *   <li>無快取 → 打 LINE → READY → 寫入</li>
 *   <li>無快取 → 打 LINE → UNREADY → 回 null + status</li>
 *   <li>LINE API 失敗 → 回舊快取（若有）+ status=ERROR</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MulticastDeliveryStatsServiceTest {

    @Mock MulticastDailyDeliveryRepository dailyRepository;
    @Mock MessagingApiClient messagingApiClient;

    private MulticastDeliveryStatsService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 27);

    @BeforeEach
    void setUp() {
        service = new MulticastDeliveryStatsService(dailyRepository, messagingApiClient);
        lenient().when(dailyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("快取命中（剛剛更新）→ 不打 LINE，回快取值")
    void getDailyTotal_freshCache_returnsCached() {
        MulticastDailyDelivery cached = MulticastDailyDelivery.builder()
                .date(TODAY)
                .lastTotal(123L)
                .updatedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.of(cached));

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isEqualTo(123L);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.fromCache()).isTrue();
        verify(messagingApiClient, never()).getNumberOfSentMulticastMessages(anyString());
    }

    @Test
    @DisplayName("無快取 → LINE READY → 寫入並回新值")
    void getDailyTotal_noCacheLineReady_savesAndReturns() {
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.empty());
        stubLineDelivery(NumberOfMessagesResponse.Status.READY, 500L);

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isEqualTo(500L);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.fromCache()).isFalse();
        verify(dailyRepository).save(any());
    }

    @Test
    @DisplayName("過期快取 + LINE READY → 更新值")
    void getDailyTotal_staleCacheLineReady_updates() {
        MulticastDailyDelivery stale = MulticastDailyDelivery.builder()
                .date(TODAY)
                .lastTotal(100L)
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.of(stale));
        stubLineDelivery(NumberOfMessagesResponse.Status.READY, 300L);

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isEqualTo(300L);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.fromCache()).isFalse();
        assertThat(stale.getLastTotal()).isEqualTo(300L); // updated in-place
    }

    @Test
    @DisplayName("過期快取 + LINE UNREADY → 不寫入，回舊快取值帶 UNREADY 狀態")
    void getDailyTotal_staleCacheLineUnready_keepsCachedValueButReportsStatus() {
        MulticastDailyDelivery stale = MulticastDailyDelivery.builder()
                .date(TODAY)
                .lastTotal(100L)
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.of(stale));
        stubLineDelivery(NumberOfMessagesResponse.Status.UNREADY, null);

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isEqualTo(100L); // 用舊快取值
        assertThat(result.status()).isEqualTo("UNREADY");
        assertThat(result.fromCache()).isTrue();
        verify(dailyRepository, never()).save(any());
    }

    @Test
    @DisplayName("無快取 + LINE UNREADY → 回 null + status")
    void getDailyTotal_noCacheLineUnready_returnsNull() {
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.empty());
        stubLineDelivery(NumberOfMessagesResponse.Status.UNREADY, null);

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isNull();
        assertThat(result.status()).isEqualTo("UNREADY");
        assertThat(result.fromCache()).isFalse();
        verify(dailyRepository, never()).save(any());
    }

    @Test
    @DisplayName("LINE API 拋例外 + 有舊快取 → 回舊值 + status=ERROR")
    void getDailyTotal_lineThrowsWithCache_returnsCachedWithErrorStatus() {
        MulticastDailyDelivery stale = MulticastDailyDelivery.builder()
                .date(TODAY)
                .lastTotal(42L)
                .updatedAt(LocalDateTime.now().minusHours(2))
                .build();
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.of(stale));
        doReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")))
                .when(messagingApiClient).getNumberOfSentMulticastMessages(anyString());

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.fromCache()).isTrue();
    }

    @Test
    @DisplayName("LINE API 拋例外 + 無快取 → 回 null + status=ERROR")
    void getDailyTotal_lineThrowsNoCache_returnsNullError() {
        when(dailyRepository.findById(TODAY)).thenReturn(Optional.empty());
        doReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")))
                .when(messagingApiClient).getNumberOfSentMulticastMessages(anyString());

        MulticastDeliveryStatsService.DailyDelivery result = service.getDailyTotal(TODAY);

        assertThat(result.total()).isNull();
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.fromCache()).isFalse();
    }

    // ── 輔助 ─────────────────────────────────────────────

    private void stubLineDelivery(NumberOfMessagesResponse.Status status, Long total) {
        NumberOfMessagesResponse body = new NumberOfMessagesResponse(status, total);
        Result<?> result = mock(Result.class);
        lenient().when(result.body()).thenReturn(body);
        doReturn(CompletableFuture.completedFuture(result))
                .when(messagingApiClient).getNumberOfSentMulticastMessages(anyString());
    }
}
