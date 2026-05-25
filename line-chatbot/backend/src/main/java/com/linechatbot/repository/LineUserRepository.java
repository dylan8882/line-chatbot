package com.linechatbot.repository;

import com.linechatbot.model.entity.LineUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * LINE 用戶資料庫存取層
 */
@Repository
public interface LineUserRepository extends JpaRepository<LineUser, Long> {

    Optional<LineUser> findByLineUserId(String lineUserId);

    boolean existsByLineUserId(String lineUserId);

    long countByStatus(String status);

    /**
     * 依關鍵字（暱稱）與標籤篩選
     */
    @Query("""
            SELECT DISTINCT u FROM LineUser u
            LEFT JOIN u.tags t
            WHERE (:keyword IS NULL OR u.displayName LIKE %:keyword%)
              AND (:status IS NULL OR u.status = :status)
              AND (:tagIds IS NULL OR t.id IN :tagIds)
            """)
    Page<LineUser> search(@Param("keyword") String keyword,
                          @Param("status") String status,
                          @Param("tagIds") List<Long> tagIds,
                          Pageable pageable);

    /**
     * 依標籤 ID 取得所有 LINE 用戶 ID（推播階段使用）
     */
    @Query("""
            SELECT DISTINCT u.lineUserId FROM LineUser u
            JOIN u.tags t
            WHERE t.id IN :tagIds AND u.status = 'FOLLOWED'
            """)
    List<String> findLineUserIdsByTagIds(@Param("tagIds") List<Long> tagIds);
}
