-- 加入好友歡迎訊息：實際訊息內容欄位（greeting_enabled 已存在）
ALTER TABLE line_channel_config
    ADD COLUMN greeting_message VARCHAR(500) NULL;
