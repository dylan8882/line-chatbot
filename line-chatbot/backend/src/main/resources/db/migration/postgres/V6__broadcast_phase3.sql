-- Phase 3：高併發核心
ALTER TABLE broadcast_chunks
    ADD COLUMN next_retry_at   TIMESTAMP,
    ADD COLUMN last_attempt_at TIMESTAMP,
    ADD COLUMN max_attempts    INT NOT NULL DEFAULT 4;

CREATE INDEX IF NOT EXISTS idx_chunk_retry ON broadcast_chunks (status, next_retry_at);
