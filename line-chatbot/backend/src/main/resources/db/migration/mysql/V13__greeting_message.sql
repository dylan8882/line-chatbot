-- 加入好友歡迎訊息：實際訊息內容欄位（greeting_enabled 已存在）
-- 純文字訊息（≤ 500 字、LINE Push API 上限為 5000 字符，這裡保守設 500 給管理介面）
ALTER TABLE line_channel_config
    ADD COLUMN greeting_message VARCHAR(500) NULL AFTER greeting_enabled;
