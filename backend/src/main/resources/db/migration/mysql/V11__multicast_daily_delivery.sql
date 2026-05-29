-- Feature B: multicast 日送達差異統計
-- multicast 模式只能拿到「當日 LINE 平台累計送達數」（GET /v2/bot/message/delivery/multicast?date=YYYYMMDD）
-- 每個任務的「日送達增量」= 本次查詢 total − 上次查詢 total
-- last_total 跨任務共享（同一天多個 multicast task 共用同一份 LINE 端累計）

CREATE TABLE multicast_daily_delivery (
    date DATE NOT NULL PRIMARY KEY,
    last_total BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL
);

-- broadcast_tasks 加欄位記錄每個 multicast task 完成後分到的「日送達增量」
-- NULL = 還未結算（task 還沒完成、或 LINE API 還沒 ready，仍有 5–10 分鐘延遲）
ALTER TABLE broadcast_tasks
    ADD COLUMN line_delivered_delta BIGINT NULL AFTER failed_count;
