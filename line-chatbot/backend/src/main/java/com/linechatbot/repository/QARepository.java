package com.linechatbot.repository;

import com.linechatbot.model.entity.QAPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 問答配對資料庫存取層
 */
@Repository
public interface QARepository extends JpaRepository<QAPair, Long> {

    /**
     * 查詢所有啟用中的 QA，依優先順序降冪排列
     */
    List<QAPair> findByIsActiveTrueOrderByPriorityDesc();
}
