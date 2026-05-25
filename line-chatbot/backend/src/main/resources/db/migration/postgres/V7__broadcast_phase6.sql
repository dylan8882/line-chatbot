-- Phase 6：加分項
ALTER TABLE broadcast_tasks
    ADD COLUMN ab_test_id            VARCHAR(64),
    ADD COLUMN variant_label         VARCHAR(20),
    ADD COLUMN narrowcast_request_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_broadcast_ab_test ON broadcast_tasks (ab_test_id);
CREATE INDEX IF NOT EXISTS idx_broadcast_narrowcast ON broadcast_tasks (narrowcast_request_id);
