-- Feature A: 推播任務新增 api_mode 欄位，可選擇 PUSH 或 MULTICAST
-- PUSH：逐一發送，能取得 per-user 送達狀態（200/4xx），預設值
-- MULTICAST：批量發送（最多 500 人/批），LINE 僅回整批 200，無 per-user 結果
ALTER TABLE broadcast_tasks
    ADD COLUMN api_mode VARCHAR(20) NOT NULL DEFAULT 'PUSH';

-- 既有資料一律當成 multicast 模式（V10 之前的歷史）
UPDATE broadcast_tasks SET api_mode = 'MULTICAST' WHERE id > 0;
