package com.linechatbot.repository;

import com.linechatbot.model.entity.BroadcastTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BroadcastTaskRepository extends JpaRepository<BroadcastTask, Long> {

    Optional<BroadcastTask> findByIdempotencyKey(String key);

    @Query("""
            SELECT t FROM BroadcastTask t
            WHERE (:status IS NULL OR t.status = :status)
            ORDER BY t.createdAt DESC
            """)
    Page<BroadcastTask> search(@Param("status") String status, Pageable pageable);

    /** 排程到時且尚未送出的任務（給 BroadcastScheduler 用） */
    @Query("""
            SELECT t FROM BroadcastTask t
            WHERE t.status = 'SCHEDULED' AND t.scheduledAt <= :now
            ORDER BY t.scheduledAt
            """)
    List<BroadcastTask> findDueScheduled(@Param("now") LocalDateTime now);

    /** 取得同一 A/B 測試組的所有 variant 任務 */
    List<BroadcastTask> findByAbTestIdOrderByVariantLabel(String abTestId);

    /** 取得仍在執行中的 narrowcast 任務（給進度 poller） */
    List<BroadcastTask> findByTargetTypeAndStatus(String targetType, String status);
}
