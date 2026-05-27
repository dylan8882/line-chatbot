package com.linechatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.BroadcastFailureDTO;
import com.linechatbot.model.dto.BroadcastStatisticsDTO;
import com.linechatbot.model.dto.ClickStatisticsDTO;
import com.linechatbot.model.entity.BroadcastChunk;
import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.model.entity.ClickLink;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import com.linechatbot.repository.ClickEventRepository;
import com.linechatbot.repository.ClickLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * BroadcastStatisticsService 單元測試：聚合統計與 CTR 計算。
 */
@ExtendWith(MockitoExtension.class)
class BroadcastStatisticsServiceTest {

    @Mock BroadcastTaskRepository taskRepository;
    @Mock BroadcastChunkRepository chunkRepository;
    @Mock ClickLinkRepository clickLinkRepository;
    @Mock ClickEventRepository clickEventRepository;

    private BroadcastStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastStatisticsService(
                taskRepository, chunkRepository, clickLinkRepository,
                clickEventRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("getStatistics：聚合各狀態 chunk 數量")
    void getStatistics_aggregatesChunkCounts() {
        BroadcastTask task = baseTask(1L, "COMPLETED");
        task.setStartedAt(LocalDateTime.now().minusMinutes(5));
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalRecipients(1500);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(chunkRepository.findByTaskIdOrderByChunkIndex(1L)).thenReturn(List.of(
                chunkOf(1, "SUCCESS", 1, "[\"U1\",\"U2\",\"U3\"]"),
                chunkOf(2, "SUCCESS", 1, "[\"U4\",\"U5\"]"),
                chunkOf(3, "FAILED", 2, "[\"U6\"]"),
                chunkOf(4, "RETRYING", 1, "[\"U7\"]"),
                chunkOf(5, "PENDING", 0, "[\"U8\"]")
        ));

        BroadcastStatisticsDTO stat = service.getStatistics(1L);

        assertThat(stat.getTaskId()).isEqualTo(1L);
        assertThat(stat.getSuccessChunks()).isEqualTo(2);
        assertThat(stat.getFailedChunks()).isEqualTo(1);
        assertThat(stat.getRetryingChunks()).isEqualTo(1);
        assertThat(stat.getPendingChunks()).isEqualTo(1);
        assertThat(stat.getDeliveredRecipients()).isEqualTo(5); // 3 + 2
    }

    @Test
    @DisplayName("getStatistics：成功率以 success/(success+failed) 計算")
    void getStatistics_successRateCalc() {
        BroadcastTask task = baseTask(1L, "COMPLETED");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(chunkRepository.findByTaskIdOrderByChunkIndex(1L)).thenReturn(List.of(
                chunkOf(1, "SUCCESS", 1, "[\"U1\"]"),
                chunkOf(2, "SUCCESS", 1, "[\"U2\"]"),
                chunkOf(3, "SUCCESS", 1, "[\"U3\"]"),
                chunkOf(4, "FAILED", 3, "[\"U4\"]")
        ));

        BroadcastStatisticsDTO stat = service.getStatistics(1L);

        assertThat(stat.getSuccessRate()).isEqualTo(3.0 / 4);
    }

    @Test
    @DisplayName("getStatistics：errorBreakdown 依 errorCode 分組")
    void getStatistics_errorBreakdownGroups() {
        BroadcastTask task = baseTask(1L, "FAILED");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(chunkRepository.findByTaskIdOrderByChunkIndex(1L)).thenReturn(List.of(
                chunkWithError(1, "TimeoutException"),
                chunkWithError(2, "TimeoutException"),
                chunkWithError(3, "ConnectException")
        ));

        BroadcastStatisticsDTO stat = service.getStatistics(1L);

        assertThat(stat.getErrorBreakdown()).hasSize(2);
        assertThat(stat.getErrorBreakdown())
                .extracting(b -> b.getErrorCode() + ":" + b.getCount())
                .containsExactlyInAnyOrder("TimeoutException:2", "ConnectException:1");
    }

    @Test
    @DisplayName("getFailures：只回傳 FAILED / RETRYING 的 chunk")
    void getFailures_filtersByStatus() {
        when(taskRepository.existsById(1L)).thenReturn(true);
        when(chunkRepository.findByTaskIdAndStatusInOrderByChunkIndex(1L, List.of("FAILED", "RETRYING")))
                .thenReturn(List.of(
                        chunkWithError(3, "TimeoutException"),
                        chunkOf(4, "RETRYING", 1, "[\"Ux\"]")
                ));

        List<BroadcastFailureDTO> failures = service.getFailures(1L);

        assertThat(failures).hasSize(2);
        assertThat(failures.get(0).getErrorCode()).isEqualTo("TimeoutException");
    }

    @Test
    @DisplayName("getFailures：任務不存在拋例外")
    void getFailures_taskNotFound_throws() {
        when(taskRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.getFailures(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── PUSH 模式：per-user 計算 ──────────────────────────────

    @Test
    @DisplayName("getStatistics PUSH：deliveredRecipients/successRate 用 task per-user 計數")
    void getStatistics_push_usesPerUserCounters() {
        BroadcastTask task = baseTask(1L, "COMPLETED");
        task.setApiMode("PUSH");
        task.setTotalRecipients(10);
        task.setSentCount(10);
        task.setSuccessCount(8);  // 8 人成功
        task.setFailedCount(2);   // 2 人 4xx 失敗
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        // 1 個 PARTIAL chunk（含 8 成 2 敗），chunk-level 算法會被忽略
        when(chunkRepository.findByTaskIdOrderByChunkIndex(1L)).thenReturn(List.of(
                chunkOf(1, "PARTIAL", 1, "[\"U1\",\"U2\",\"U3\",\"U4\",\"U5\",\"U6\",\"U7\",\"U8\",\"U9\",\"U10\"]")
        ));

        BroadcastStatisticsDTO stat = service.getStatistics(1L);

        assertThat(stat.getDeliveredRecipients()).isEqualTo(8);
        assertThat(stat.getSuccessRate()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("getStatistics PUSH：成功+失敗皆 0 時 successRate=0 不除以零")
    void getStatistics_push_zeroCounters_noDivByZero() {
        BroadcastTask task = baseTask(1L, "DRAFT");
        task.setApiMode("PUSH");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(chunkRepository.findByTaskIdOrderByChunkIndex(1L)).thenReturn(List.of());

        BroadcastStatisticsDTO stat = service.getStatistics(1L);

        assertThat(stat.getSuccessRate()).isZero();
        assertThat(stat.getDeliveredRecipients()).isZero();
    }

    // ── Phase 7 click stats ─────────────────────────────────────

    @Test
    @DisplayName("getClickStatistics：CTR = totalClicks / deliveredRecipients")
    void getClickStatistics_ctrCalc() {
        BroadcastTask task = baseTask(1L, "COMPLETED");
        task.setSuccessCount(1000);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(clickLinkRepository.findByTaskIdOrderByLinkIndex(1L)).thenReturn(List.of(
                clickLink(1L, 0, "https://a.com", 50),
                clickLink(2L, 1, "https://b.com", 30)
        ));
        when(clickEventRepository.countByTaskId(1L)).thenReturn(80L);
        when(clickEventRepository.countDistinctIpByTaskId(1L)).thenReturn(45L);

        ClickStatisticsDTO stat = service.getClickStatistics(1L);

        assertThat(stat.getTotalClicks()).isEqualTo(80);
        assertThat(stat.getUniqueIps()).isEqualTo(45);
        assertThat(stat.getDeliveredRecipients()).isEqualTo(1000);
        assertThat(stat.getCtr()).isEqualTo(80.0 / 1000);
        assertThat(stat.getLinks()).hasSize(2);
    }

    @Test
    @DisplayName("getClickStatistics：deliveredRecipients=0 時 CTR=0 不除以零")
    void getClickStatistics_zeroDelivered_ctrZero() {
        BroadcastTask task = baseTask(1L, "DRAFT");
        task.setSuccessCount(0);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(clickLinkRepository.findByTaskIdOrderByLinkIndex(1L)).thenReturn(List.of());
        when(clickEventRepository.countByTaskId(1L)).thenReturn(0L);
        when(clickEventRepository.countDistinctIpByTaskId(1L)).thenReturn(0L);

        ClickStatisticsDTO stat = service.getClickStatistics(1L);
        assertThat(stat.getCtr()).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────

    private BroadcastTask baseTask(Long id, String status) {
        // 預設 MULTICAST 讓既有測試走 chunk-level 計算；PUSH 測試需自行 override
        return BroadcastTask.builder()
                .id(id)
                .name("t-" + id)
                .messageContent("[]")
                .targetType("ALL")
                .apiMode("MULTICAST")
                .status(status)
                .totalRecipients(0)
                .sentCount(0)
                .successCount(0)
                .failedCount(0)
                .build();
    }

    private BroadcastChunk chunkOf(int idx, String status, int attempts, String recipientIds) {
        return BroadcastChunk.builder()
                .id((long) idx)
                .taskId(1L)
                .chunkIndex(idx)
                .status(status)
                .attempts(attempts)
                .recipientIds(recipientIds)
                .build();
    }

    private BroadcastChunk chunkWithError(int idx, String errorCode) {
        BroadcastChunk c = chunkOf(idx, "FAILED", 3, "[\"Ufail\"]");
        c.setErrorCode(errorCode);
        c.setErrorMessage("error " + idx);
        return c;
    }

    private ClickLink clickLink(Long id, int linkIndex, String url, int clickCount) {
        return ClickLink.builder()
                .id(id)
                .taskId(1L)
                .linkIndex(linkIndex)
                .targetUrl(url)
                .token("tok" + id)
                .clickCount(clickCount)
                .build();
    }
}
