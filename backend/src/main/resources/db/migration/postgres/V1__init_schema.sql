-- 管理員用戶
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 問答設定
CREATE TABLE IF NOT EXISTS qa_pairs (
    id          BIGSERIAL PRIMARY KEY,
    keyword     VARCHAR(500) NOT NULL,
    answer      TEXT NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    priority    INT NOT NULL DEFAULT 0,
    match_type  VARCHAR(20) NOT NULL DEFAULT 'CONTAINS',
    created_by  BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_qa_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 訊息紀錄
CREATE TABLE IF NOT EXISTS message_logs (
    id              BIGSERIAL PRIMARY KEY,
    line_user_id    VARCHAR(100) NOT NULL,
    message_text    TEXT NOT NULL,
    response_text   TEXT,
    response_type   VARCHAR(20) NOT NULL,
    qa_pair_id      BIGINT,
    latency_ms      INT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_created_at ON message_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_line_user_id ON message_logs (line_user_id);

-- 自動更新 updated_at 觸發器
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_qa_pairs_updated_at BEFORE UPDATE ON qa_pairs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
