package com.linechatbot.repository;

import com.linechatbot.model.entity.LineChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * LINE 頻道設定 Repository。
 * 資料庫固定只有一筆（id = 1）。
 */
@Repository
public interface LineChannelConfigRepository extends JpaRepository<LineChannelConfig, Long> {
}
