-- Phase 1：推播功能基礎建設
-- 1) LINE 用戶資料表（Follow webhook 觸發建檔）
CREATE TABLE IF NOT EXISTS line_users (
    id              BIGSERIAL PRIMARY KEY,
    line_user_id    VARCHAR(100) NOT NULL UNIQUE,
    display_name    VARCHAR(100),
    picture_url     VARCHAR(500),
    status_message  VARCHAR(255),
    language        VARCHAR(10),
    status          VARCHAR(20) NOT NULL DEFAULT 'FOLLOWED',
    followed_at     TIMESTAMP,
    unfollowed_at   TIMESTAMP,
    last_message_at TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_line_users_status ON line_users (status);
CREATE INDEX IF NOT EXISTS idx_line_users_followed_at ON line_users (followed_at);

-- 2) 標籤
CREATE TABLE IF NOT EXISTS tags (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    color       VARCHAR(7) NOT NULL DEFAULT '#1677ff',
    description VARCHAR(200),
    user_count  INT NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tags_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 3) 用戶 ↔ 標籤（多對多）
CREATE TABLE IF NOT EXISTS user_tags (
    line_user_id BIGINT NOT NULL,
    tag_id       BIGINT NOT NULL,
    tagged_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tagged_by    BIGINT,
    PRIMARY KEY (line_user_id, tag_id),
    CONSTRAINT fk_user_tags_line_user FOREIGN KEY (line_user_id) REFERENCES line_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_tags_tagged_by FOREIGN KEY (tagged_by) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_user_tags_tag_id ON user_tags (tag_id, line_user_id);

-- 沿用 V1 的 update_updated_at_column() 觸發器
CREATE TRIGGER update_line_users_updated_at BEFORE UPDATE ON line_users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_tags_updated_at BEFORE UPDATE ON tags
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
