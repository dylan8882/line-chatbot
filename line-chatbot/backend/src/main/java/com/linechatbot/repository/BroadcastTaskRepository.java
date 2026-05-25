package com.linechatbot.repository;

import com.linechatbot.model.entity.BroadcastTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
