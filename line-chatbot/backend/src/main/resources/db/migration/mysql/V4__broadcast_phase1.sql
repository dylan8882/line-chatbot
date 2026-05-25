-- Phase 1：推播功能基礎建設
-- 1) LINE 用戶資料表（Follow webhook 觸發建檔）
CREATE TABLE IF NOT EXISTS line_users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_user_id    VARCHAR(100) NOT NULL UNIQUE,
    display_name    VARCHAR(100),
    picture_url     VARCHAR(500),
    status_message  VARCHAR(255),
    language        VARCHAR(10),
    status          VARCHAR(20) NOT NULL DEFAULT 'FOLLOWED',
    followed_at     DATETIME,
    unfollowed_at   DATETIME,
    last_message_at DATETIME,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_line_users_status (status),
    INDEX idx_line_users_followed_at (followed_at)
);

-- 2) 標籤
CREATE TABLE IF NOT EXISTS tags (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    color       VARCHAR(7) NOT NULL DEFAULT '#1677ff',
    description VARCHAR(200),
    user_count  INT NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 3) 用戶 ↔ 標籤（多對多）
CREATE TABLE IF NOT EXISTS user_tags (
    line_user_id BIGINT NOT NULL,
    tag_id       BIGINT NOT NULL,
    tagged_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tagged_by    BIGINT,
    PRIMARY KEY (line_user_id, tag_id),
    INDEX idx_user_tags_tag_id (tag_id, line_user_id),
    FOREIGN KEY (line_user_id) REFERENCES line_users(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE,
    FOREIGN KEY (tagged_by) REFERENCES users(id) ON DELETE SET NULL
);
