-- Phase 3：高併發核心
-- 新增 chunk 重試所需欄位
ALTER TABLE broadcast_chunks
    ADD COLUMN next_retry_at  DATETIME NULL AFTER attempts,
    ADD COLUMN last_attempt_at DATETIME NULL AFTER next_retry_at,
    ADD COLUMN max_attempts   INT NOT NULL DEFAULT 4 AFTER last_attempt_at;

CREATE INDEX idx_chunk_retry ON broadcast_chunks (status, next_retry_at);
