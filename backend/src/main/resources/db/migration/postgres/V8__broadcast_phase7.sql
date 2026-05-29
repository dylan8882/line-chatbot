-- Phase 7：點擊追蹤
CREATE TABLE IF NOT EXISTS click_links (
    id           BIGSERIAL PRIMARY KEY,
    task_id      BIGINT NOT NULL,
    link_index   INT NOT NULL,
    target_url   VARCHAR(2000) NOT NULL,
    token        VARCHAR(32) NOT NULL UNIQUE,
    click_count  INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_click_links_task FOREIGN KEY (task_id) REFERENCES broadcast_tasks(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_click_links_task ON click_links (task_id, link_index);

CREATE TABLE IF NOT EXISTS click_events (
    id           BIGSERIAL PRIMARY KEY,
    link_id      BIGINT NOT NULL,
    task_id      BIGINT NOT NULL,
    user_agent   VARCHAR(500),
    ip           VARCHAR(45),
    referer      VARCHAR(500),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_click_events_link FOREIGN KEY (link_id) REFERENCES click_links(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_click_events_task ON click_events (task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_click_events_link ON click_events (link_id, created_at);
