package com.linechatbot.service;

import com.linechatbot.model.entity.BroadcastTask;
import com.linechatbot.repository.BroadcastChunkRepository;
import com.linechatbot.repository.BroadcastTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 推播任務計數器：將 sent/success/failed 寫入 Redis 計數器（INCR 原子操作），
 * 並由排程 worker 定期 flush 回 DB，避免每筆 chunk 完成都打到 broadcast_tasks 同一列（hot row）。
 *
 * <p><b>Redis Keys</b>
 * <ul>
 *   <li>{@code broadcast:task:{id}:sent / :success / :failed} — 累計計數</li>
 *   <li>{@code broadcast:task:{id}:total} — 總收件人數，由 submit 時設定</li>
 *   <li>{@code broadcast:dirty:tasks} — 有變更的任務 ID 集合，flusher 取出後同步至 DB</li>
 * </ul>
 *
 * <p><b>原子終止判斷</b>：INCR sent 與「是否達到 total」在同一個 Lua 中完成，
 * 保證並行 worker 中「恰好一個」收到 `isLast=1` 訊號，可放心觸發 finalize。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastCounterService {

    public static final String DIRTY_SET = "broadcast:dirty:tasks";

    /** Lua：INCR sent + 同時加 success/failed 並回傳是否抵達 total */
    private static final String SCRIPT_INCR = """
            local sentKey = KEYS[1]
            local successKey = KEYS[2]
            local failedKey = KEYS[3]
            local totalKey = KEYS[4]
            local dirtyKey = KEYS[5]
            local taskId = ARGV[1]
            local sentDelta = tonumber(ARGV[2])
            local successDelta = tonumber(ARGV[3])
            local failedDelta = tonumber(ARGV[4])

            local newSent = redis.call('INCRBY', sentKey, sentDelta)
            if successDelta > 0 then redis.call('INCRBY', successKey, successDelta) end
            if failedDelta > 0 then redis.call('INCRBY', failedKey, failedDelta) end
            redis.call('SADD', dirtyKey, taskId)

            local total = tonumber(redis.call('GET', totalKey)) or -1
            if total > 0 and newSent >= total then
                return 1
            else
                return 0
            end
            """;

    private final StringRedisTemplate redisTemplate;
    private final BroadcastTaskRepository taskRepository;
    private final BroadcastChunkRepository chunkRepository;

    private DefaultRedisScript<Long> incrScript;

    @PostConstruct
    public void init() {
        incrScript = new DefaultRedisScript<>(SCRIPT_INCR, Long.class);
    }

    /** 任務提交時呼叫：初始化 Redis 計數器，避免後續 INCR 從錯誤值起算 */
    public void initTask(Long taskId, int totalRecipients) {
        redisTemplate.opsForValue().set(sentKey(taskId), "0");
        redisTemplate.opsForValue().set(successKey(taskId), "0");
        redisTemplate.opsForValue().set(failedKey(taskId), "0");
        redisTemplate.opsForValue().set(totalKey(taskId), String.valueOf(totalRecipients));
        redisTemplate.opsForSet().add(DIRTY_SET, String.valueOf(taskId));
    }

    /**
     * 累計一個 chunk 的結果。
     *
     * @return true 若這次累計後 sent >= total（即本次是最後一筆，呼叫方應觸發 finalize）
     */
    public boolean recordChunkResult(Long taskId, int sentDelta, int successDelta, int failedDelta) {
        Long isLast = redisTemplate.execute(
                incrScript,
                List.of(sentKey(taskId), successKey(taskId), failedKey(taskId), totalKey(taskId), DIRTY_SET),
                String.valueOf(taskId),
                String.valueOf(sentDelta),
                String.valueOf(successDelta),
                String.valueOf(failedDelta)
        );
        return isLast != null && isLast == 1L;
    }

    /**
     * 將任務的 Redis 計數同步回 DB。完成後不清除 Redis（保留為查詢來源），
     * 直到 finalize 時才一併清除。
     */
    @Transactional
    public void flushTask(Long taskId) {
        Optional<BroadcastTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            redisTemplate.opsForSet().remove(DIRTY_SET, String.valueOf(taskId));
            return;
        }
        BroadcastTask task = taskOpt.get();

        Integer sent = readInt(sentKey(taskId));
        Integer success = readInt(successKey(taskId));
        Integer failed = readInt(failedKey(taskId));

        if (sent != null) task.setSentCount(sent);
        if (success != null) task.setSuccessCount(success);
        if (failed != null) task.setFailedCount(failed);
        taskRepository.save(task);
    }

    /**
     * 任務終止時清除所有 Redis 計數鍵。
     */
    public void clearTask(Long taskId) {
        redisTemplate.delete(List.of(
                sentKey(taskId), successKey(taskId), failedKey(taskId), totalKey(taskId)
        ));
        redisTemplate.opsForSet().remove(DIRTY_SET, String.valueOf(taskId));
    }

    /**
     * 排程：每 5 秒將 dirty 任務的計數同步回 DB。
     */
    @Scheduled(fixedDelayString = "${broadcast.counter.flush-interval-ms:5000}")
    public void flushDirtyTasks() {
        Set<String> dirty = redisTemplate.opsForSet().members(DIRTY_SET);
        if (dirty == null || dirty.isEmpty()) return;

        log.debug("Flush {} dirty broadcast tasks", dirty.size());
        for (String s : dirty) {
            try {
                Long taskId = Long.valueOf(s);
                flushTask(taskId);
                redisTemplate.opsForSet().remove(DIRTY_SET, s);
            } catch (Exception e) {
                log.warn("Flush task {} 失敗：{}", s, e.getMessage());
            }
        }
    }

    /**
     * 任務派發收尾：依 chunk 狀態決定 final status。
     */
    @Transactional
    public void finalizeTask(Long taskId) {
        BroadcastTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        if ("CANCELLED".equals(task.getStatus())) {
            clearTask(taskId);
            return;
        }

        // 同步最新計數到 DB
        flushTask(taskId);
        task = taskRepository.findById(taskId).orElseThrow();

        long failed = chunkRepository.countByTaskIdAndStatus(taskId, "FAILED");
        long success = chunkRepository.countByTaskIdAndStatus(taskId, "SUCCESS");
        long pending = chunkRepository.countByTaskIdAndStatusIn(taskId,
                List.of("PENDING", "SENDING", "RETRYING"));

        if (pending > 0) {
            log.warn("finalize 被呼叫但仍有 pending chunk：taskId={}, pending={}", taskId, pending);
            return;
        }

        if (failed == 0) {
            task.setStatus("COMPLETED");
            task.setErrorMessage(null);
        } else if (success == 0) {
            task.setStatus("FAILED");
            task.setErrorMessage("所有分片均失敗");
        } else {
            task.setStatus("COMPLETED");
            task.setErrorMessage("部分分片失敗（success=" + success + ", failed=" + failed + "）");
        }
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);

        clearTask(taskId);
        log.info("任務派發結束：taskId={}, status={}, success={}, failed={}",
                taskId, task.getStatus(), success, failed);
    }

    // ── 工具 ──────────────────────────────────────────────────

    private Integer readInt(String key) {
        String v = redisTemplate.opsForValue().get(key);
        return v == null ? null : Integer.valueOf(v);
    }

    public static String sentKey(Long taskId) { return "broadcast:task:" + taskId + ":sent"; }
    public static String successKey(Long taskId) { return "broadcast:task:" + taskId + ":success"; }
    public static String failedKey(Long taskId) { return "broadcast:task:" + taskId + ":failed"; }
    public static String totalKey(Long taskId) { return "broadcast:task:" + taskId + ":total"; }
}
