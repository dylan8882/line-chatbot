package com.linechatbot.repository;

import com.linechatbot.model.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 標籤資料庫存取層
 */
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    boolean existsByName(String name);

    List<Tag> findAllByOrderByCreatedAtDesc();

    /**
     * 重新計算指定標籤的用戶數（user_count），用於批量操作後同步反正規化欄位
     */
    @Modifying
    @Query(value = """
            UPDATE tags t
            SET user_count = (
                SELECT COUNT(*) FROM user_tags ut WHERE ut.tag_id = t.id
            )
            WHERE t.id IN (:tagIds)
            """, nativeQuery = true)
    void refreshUserCount(@Param("tagIds") List<Long> tagIds);
}
