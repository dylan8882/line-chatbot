-- Phase 2：訊息模板與推播任務
-- 1) 訊息模板
CREATE TABLE IF NOT EXISTS message_templates (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    message_type  VARCHAR(20) NOT NULL,
    content       JSONB NOT NULL,
    thumbnail     VARCHAR(500),
    created_by    BIGINT,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_template_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 2) 推播任務
CREATE TABLE IF NOT EXISTS broadcast_tasks (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    message_content   JSONB NOT NULL,
    target_type       VARCHAR(20) NOT NULL,
    target_filter     JSONB,
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    total_recipients  INT NOT NULL DEFAULT 0,
    sent_count        INT NOT NULL DEFAULT 0,
    success_count     INT NOT NULL DEFAULT 0,
    failed_count      INT NOT NULL DEFAULT 0,
    scheduled_at      TIMESTAMP,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    idempotency_key   VARCHAR(64) UNIQUE,
    error_message     TEXT,
    created_by        BIGINT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_broadcast_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_broadcast_status ON broadcast_tasks (status, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_broadcast_created ON broadcast_tasks (created_at DESC);

-- 3) 推播分片
CREATE TABLE IF NOT EXISTS broadcast_chunks (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT NOT NULL,
    chunk_index       INT NOT NULL,
    recipient_ids     JSONB NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts          INT NOT NULL DEFAULT 0,
    line_request_id   VARCHAR(100),
    error_code        VARCHAR(50),
    error_message     TEXT,
    sent_at           TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chunk_task FOREIGN KEY (task_id) REFERENCES broadcast_tasks(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chunk_task ON broadcast_chunks (task_id, status);
CREATE INDEX IF NOT EXISTS idx_chunk_status ON broadcast_chunks (status, attempts);

-- 沿用 V1 的觸發器
CREATE TRIGGER update_message_templates_updated_at BEFORE UPDATE ON message_templates
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_broadcast_tasks_updated_at BEFORE UPDATE ON broadcast_tasks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_broadcast_chunks_updated_at BEFORE UPDATE ON broadcast_chunks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
