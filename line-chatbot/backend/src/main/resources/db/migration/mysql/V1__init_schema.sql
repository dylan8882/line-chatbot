-- 管理員用戶
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 問答設定
CREATE TABLE IF NOT EXISTS qa_pairs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    keyword     VARCHAR(500) NOT NULL,
    answer      TEXT NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    priority    INT NOT NULL DEFAULT 0,
    match_type  VARCHAR(20) NOT NULL DEFAULT 'CONTAINS',
    created_by  BIGINT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 訊息紀錄
CREATE TABLE IF NOT EXISTS message_logs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_user_id    VARCHAR(100) NOT NULL,
    message_text    TEXT NOT NULL,
    response_text   TEXT,
    response_type   VARCHAR(20) NOT NULL,
    qa_pair_id      BIGINT,
    latency_ms      INT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at),
    INDEX idx_line_user_id (line_user_id)
);
