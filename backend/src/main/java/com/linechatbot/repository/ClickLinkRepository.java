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

    /** 原子遞增 click_count */
    @Modifying
    @Query("UPDATE ClickLink l SET l.clickCount = l.clickCount + 1 WHERE l.id = :id")
    void incrementClickCount(@Param("id") Long id);
}
