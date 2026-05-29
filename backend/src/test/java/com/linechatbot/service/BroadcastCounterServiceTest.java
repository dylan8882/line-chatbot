package com.linechatbot.service;

import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BroadcastCounterService 單元測試。
 *
 * <p>Mock RedisTemplate 的 execute() 行為模擬 Lua 回傳，
 * 驗證 isLast 判斷、計數初始化/清除、finalize 狀態轉換。
 */
@ExtendWith(MockitoExtension.class)
class BroadcastCounterServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;
    @Mock BroadcastTaskRepository taskRepository;
    @Mock BroadcastChunkRepository chunkRepository;
    @Mock BroadcastProgressService progressService;

    private BroadcastCounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new BroadcastCounterService(
                redisTemplate, taskRepository, chunkRepository, progressService);
        counterService.init(); // 初始化 Lua script

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    @DisplayName("initTask：寫入 4 個計數鍵 + SADD dirty set")
    void initTask_setsAllKeys() {
        counterService.initTask(10L, 200);

        verify(valueOps).set("broadcast:task:10:sent", "0");
        verify(valueOps).set("broadcast:task:10:success", "0");
        verify(valueOps).set("broadcast:task:10:failed", "0");
        verify(valueOps).set("broadcast:task:10:total", "200");
        verify(setOps).add("broadcast:dirty:tasks", "10");
    }

    @Test
    @DisplayName("recordChunkResult：Lua 回傳 1L 表示這次是最後一筆")
    void recordChunkResult_luaReturnsOne_isLastTrue() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                any(), any(), any(), any())).thenReturn(1L);

        boolean isLast = counterService.recordChunkResult(10L, 500, 500, 0);

        assertThat(isLast).isTrue();
        verify(progressService).publish(any());
    }

    @Test
    @DisplayName("recordChunkResult：Lua 回傳 0L 表示還未結束")
    void recordChunkResult_luaReturnsZero_isLastFalse() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                any(), any(), any(), any())).thenReturn(0L);

        boolean isLast = counterService.recordChunkResult(10L, 500, 480, 20);

        assertThat(isLast).isFalse();
    }

    @Test
    @DisplayName("clearTask：刪除 4 個 Redis 計數鍵與 dirty set 成員")
    @SuppressWarnings("unchecked")
    void clearTask_deletesAllKeys() {
        counterService.clearTask(10L);

        // delete(List) 應被呼叫；用任何 Collection 匹配
        verify(redisTemplate).delete((Collection<String>) any());
        verify(setOps).remove("broadcast:dirty:tasks", "10");
    }

    @Test
    @DisplayName("finalizeTask：所有 chunk SUCCESS → 任務 COMPLETED")
    void finalize_allSuccess_setsCompleted() {
        BroadcastTask task = baseTask(10L, "RUNNING");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(chunkRepository.countByTaskIdAndStatus(10L, "FAILED")).thenReturn(0L);
        when(chunkRepository.countByTaskIdAndStatus(10L, "SUCCESS")).thenReturn(5L);
        when(chunkRepository.countByTaskIdAndStatusIn(eq(10L), any())).thenReturn(0L);
        stubReadInt();

        counterService.finalizeTask(10L);

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getFinishedAt()).isNotNull();
        verify(progressService).publish(any());
    }

    @Test
    @DisplayName("finalizeTask：全部失敗 → FAILED + errorMessage")
    void finalize_allFailed_setsFailed() {
        BroadcastTask task = baseTask(10L, "RUNNING");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(chunkRepository.countByTaskIdAndStatus(10L, "FAILED")).thenReturn(5L);
        when(chunkRepository.countByTaskIdAndStatus(10L, "SUCCESS")).thenReturn(0L);
        when(chunkRepository.countByTaskIdAndStatusIn(eq(10L), any())).thenReturn(0L);
        stubReadInt();

        counterService.finalizeTask(10L);

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getErrorMessage()).contains("所有");
    }

    @Test
    @DisplayName("finalizeTask：部分失敗 → COMPLETED 但記錄訊息")
    void finalize_partialFailure_setsCompletedWithWarning() {
        BroadcastTask task = baseTask(10L, "RUNNING");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(chunkRepository.countByTaskIdAndStatus(10L, "FAILED")).thenReturn(2L);
        when(chunkRepository.countByTaskIdAndStatus(10L, "SUCCESS")).thenReturn(3L);
        when(chunkRepository.countByTaskIdAndStatusIn(eq(10L), any())).thenReturn(0L);
        stubReadInt();

        counterService.finalizeTask(10L);

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getErrorMessage()).contains("部分");
    }

    @Test
    @DisplayName("finalizeTask：仍有 pending chunk 時不變更 task")
    void finalize_pendingChunks_doesNothing() {
        BroadcastTask task = baseTask(10L, "RUNNING");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(chunkRepository.countByTaskIdAndStatus(10L, "FAILED")).thenReturn(0L);
        when(chunkRepository.countByTaskIdAndStatus(10L, "SUCCESS")).thenReturn(2L);
        when(chunkRepository.countByTaskIdAndStatusIn(eq(10L), any())).thenReturn(3L);
        stubReadInt();

        counterService.finalizeTask(10L);

        assertThat(task.getStatus()).isEqualTo("RUNNING");
        verify(progressService, never()).publish(any());
    }

    @Test
    @DisplayName("finalizeTask：已 CANCELLED 不再變更，只清 Redis")
    void finalize_alreadyCancelled_onlyClears() {
        BroadcastTask task = baseTask(10L, "CANCELLED");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        counterService.finalizeTask(10L);

        assertThat(task.getStatus()).isEqualTo("CANCELLED");
        verify(progressService, never()).publish(any());
    }

    @Test
    @DisplayName("flushDirtyTasks：對每個 dirty taskId 同步並從 set 移除")
    void flushDirtyTasks_processesEachTaskInDirtySet() {
        when(setOps.members("broadcast:dirty:tasks")).thenReturn(Set.of("10", "20"));
        BroadcastTask t10 = baseTask(10L, "RUNNING");
        BroadcastTask t20 = baseTask(20L, "RUNNING");
        when(taskRepository.findById(10L)).thenReturn(Optional.of(t10));
        when(taskRepository.findById(20L)).thenReturn(Optional.of(t20));
        stubReadInt();

        counterService.flushDirtyTasks();

        verify(setOps).remove("broadcast:dirty:tasks", "10");
        verify(setOps).remove("broadcast:dirty:tasks", "20");
    }

    // ── helpers ─────────────────────────────────────────────────

    private void stubReadInt() {
        // valueOps.get(any) 預設回 null → readInt 回 null（不影響 finalizeTask 主邏輯）
        lenient().when(valueOps.get(anyString())).thenReturn(null);
    }

    private BroadcastTask baseTask(Long id, String status) {
        return BroadcastTask.builder()
                .id(id)
                .name("t-" + id)
                .messageContent("[]")
                .targetType("ALL")
                .status(status)
                .totalRecipients(0)
                .sentCount(0)
                .successCount(0)
                .failedCount(0)
                .build();
    }
}
