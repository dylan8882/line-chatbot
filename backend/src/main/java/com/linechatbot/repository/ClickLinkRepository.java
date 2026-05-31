package com.linechatbot.repository;

import com.linechatbot.model.entity.ClickLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClickLinkRepository extends JpaRepository<ClickLink, Long> {

    Optional<ClickLink> findByToken(String token);

    List<ClickLink> findByTaskIdOrderByLinkIndex(Long taskId);

    /**
     * 把指定 delta 加到 click_count（用於 Redis 累計後批次回寫）。
     * <p>原本一筆點擊 = 一次 UPDATE +1，高 QPS 下會在同一 row 上發生行鎖序列化。
     * 改成 Redis INCR 累積、排程每 N 秒一次批次回寫，DB 寫入頻率降 N×rate 倍。
     */
    @Modifying
    @Query("UPDATE ClickLink l SET l.clickCount = l.clickCount + :delta WHERE l.id = :id")
    void addClickCount(@Param("id") Long id, @Param("delta") long delta);
}
