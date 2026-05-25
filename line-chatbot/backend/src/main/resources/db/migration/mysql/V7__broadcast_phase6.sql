-- Phase 6：加分項
-- 新增 A/B 測試、Narrowcast 所需欄位
ALTER TABLE broadcast_tasks
    ADD COLUMN ab_test_id            VARCHAR(64)  NULL AFTER idempotency_key,
    ADD COLUMN variant_label         VARCHAR(20)  NULL AFTER ab_test_id,
    ADD COLUMN narrowcast_request_id VARCHAR(100) NULL AFTER variant_label;

CREATE INDEX idx_broadcast_ab_test ON broadcast_tasks (ab_test_id);
CREATE INDEX idx_broadcast_narrowcast ON broadcast_tasks (narrowcast_request_id);
