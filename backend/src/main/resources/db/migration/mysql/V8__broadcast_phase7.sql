-- Phase 7：點擊追蹤
-- 1) click_links：每個任務送出前，每個 URL 都會建立一筆，含短 token
CREATE TABLE IF NOT EXISTS click_links (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id      BIGINT NOT NULL,
    link_index   INT NOT NULL,
    target_url   VARCHAR(2000) NOT NULL,
    token        VARCHAR(32) NOT NULL UNIQUE,
    click_count  INT NOT NULL DEFAULT 0,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES broadcast_tasks(id) ON DELETE CASCADE,
    INDEX idx_click_links_task (task_id, link_index)
);

-- 2) click_events：每次點擊記一筆（用於明細查詢、IP / UA 分析）
CREATE TABLE IF NOT EXISTS click_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    link_id      BIGINT NOT NULL,
    task_id      BIGINT NOT NULL,
    user_agent   VARCHAR(500),
    ip           VARCHAR(45),
    referer      VARCHAR(500),
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (link_id) REFERENCES click_links(id) ON DELETE CASCADE,
    INDEX idx_click_events_task (task_id, created_at),
    INDEX idx_click_events_link (link_id, created_at)
);
