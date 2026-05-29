package com.linechatbot.repository;

import com.linechatbot.model.entity.MessageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 訊息紀錄資料庫存取層
 */
@Repository
public interface MessageLogRepository extends JpaRepository<MessageLog, Long> {

    /**
     * 分頁查詢訊息紀錄，按建立時間降冪排列
     */
    Page<MessageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 查詢指定時間區間的訊息數量
     */
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 查詢指定時間區間、指定回應類型的數量
     */
    long countByCreatedAtBetweenAndResponseType(LocalDateTime start, LocalDateTime end, String responseType);

    /**
     * 查詢指定時間區間的平均延遲（毫秒）
     */
    @Query("SELECT AVG(m.latencyMs) FROM MessageLog m WHERE m.createdAt BETWEEN :start AND :end AND m.latencyMs IS NOT NULL")
    Double findAvgLatencyBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 查詢每日訊息統計（依日期分組）
     */
    @Query(value = """
            SELECT DATE(created_at) as date,
                   COUNT(*) as total,
                   SUM(CASE WHEN response_type = 'QA' THEN 1 ELSE 0 END) as qa_count,
                   SUM(CASE WHEN response_type = 'AI' THEN 1 ELSE 0 END) as ai_count
            FROM message_logs
            WHERE created_at >= :since
            GROUP BY DATE(created_at)
            ORDER BY date ASC
            """, nativeQuery = true)
    List<Object[]> findDailyStatsSince(@Param("since") LocalDateTime since);
}
