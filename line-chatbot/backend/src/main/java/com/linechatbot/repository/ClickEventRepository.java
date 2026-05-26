package com.linechatbot.repository;

import com.linechatbot.model.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByTaskId(Long taskId);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(DISTINCT e.ip) FROM ClickEvent e WHERE e.taskId = :taskId AND e.ip IS NOT NULL"
    )
    long countDistinctIpByTaskId(@org.springframework.data.repository.query.Param("taskId") Long taskId);
}
