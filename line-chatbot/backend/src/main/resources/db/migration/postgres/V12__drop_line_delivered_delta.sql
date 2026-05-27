-- 撤回：multicast per-task delta 概念在同日多任務時被結算次序左右、不具歸因意義，
-- 改為「當日 LINE 平台累計送達」儀表板 widget（multicast_daily_delivery 表保留作為快取）。
ALTER TABLE broadcast_tasks DROP COLUMN line_delivered_delta;
