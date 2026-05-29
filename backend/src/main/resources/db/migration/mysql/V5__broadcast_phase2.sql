-- Phase 2：訊息模板與推播任務
-- 1) 訊息模板（可重用的 Text / Flex 內容）
CREATE TABLE IF NOT EXISTS message_templates (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    message_type  VARCHAR(20) NOT NULL,                 -- TEXT / FLEX / IMAGE / TEMPLATE
    content       JSON NOT NULL,                        -- LINE Messages 物件陣列
    thumbnail     VARCHAR(500),
    created_by    BIGINT,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 2) 推播任務
CREATE TABLE IF NOT EXISTS broadcast_tasks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    message_content   JSON NOT NULL,                     -- 送出當下的訊息 snapshot
    target_type       VARCHAR(20) NOT NULL,              -- ALL / TAGS / USER_LIST
    target_filter     JSON,                              -- {tag_ids:[...], match:"ANY|ALL"} 或 {user_ids:[...]}
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                                                          -- DRAFT / QUEUED / RUNNING / PAUSED
                                                          -- / COMPLETED / FAILED / CANCELLED
    total_recipients  INT NOT NULL DEFAULT 0,
    sent_count        INT NOT NULL DEFAULT 0,
    success_count     INT NOT NULL DEFAULT 0,
    failed_count      INT NOT NULL DEFAULT 0,
    scheduled_at      DATETIME,
    started_at        DATETIME,
    finished_at       DATETIME,
    idempotency_key   VARCHAR(64) UNIQUE,
    error_message     TEXT,
    created_by        BIGINT,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_broadcast_status (status, scheduled_at),
    INDEX idx_broadcast_created (created_at DESC)
);

-- 3) 推播分片（每 500 人一片，對應一次 multicast 呼叫）
CREATE TABLE IF NOT EXISTS broadcast_chunks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id           BIGINT NOT NULL,
    chunk_index       INT NOT NULL,
    recipient_ids     JSON NOT NULL,                      -- 該片的 LINE userId 陣列
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                                          -- PENDING / SENDING / SUCCESS / FAILED / RETRYING
    attempts          INT NOT NULL DEFAULT 0,
    line_request_id   VARCHAR(100),
    error_code        VARCHAR(50),
    error_message     TEXT,
    sent_at           DATETIME,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES broadcast_tasks(id) ON DELETE CASCADE,
    INDEX idx_chunk_task (task_id, status),
    INDEX idx_chunk_status (status, attempts)
);
