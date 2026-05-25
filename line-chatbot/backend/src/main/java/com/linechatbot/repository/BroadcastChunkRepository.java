package com.linechatbot.repository;

import com.linechatbot.model.entity.BroadcastChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BroadcastChunkRepository extends JpaRepository<BroadcastChunk, Long> {

    List<BroadcastChunk> findByTaskIdOrderByChunkIndex(Long taskId);

    long countByTaskIdAndStatus(Long taskId, String status);

    /** 任務的失敗 chunk 數，用於決定整個任務最終狀態 */
    long countByTaskIdAndStatusIn(Long taskId, List<String> statuses);

    /** 失敗或重試中的 chunk（給失敗清單頁用） */
    List<BroadcastChunk> findByTaskIdAndStatusInOrderByChunkIndex(Long taskId, List<String> statuses);
}
