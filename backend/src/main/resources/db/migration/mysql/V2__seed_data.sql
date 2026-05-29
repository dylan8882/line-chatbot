-- 範例問答（admin 帳號由 Spring Boot DataInitializer 在啟動時建立）
INSERT IGNORE INTO qa_pairs (keyword, answer, is_active, priority, match_type, created_by)
VALUES ('你好', '您好！我是 LINE 智能客服機器人，有什麼可以幫助您的嗎？', TRUE, 100, 'CONTAINS', NULL);

INSERT IGNORE INTO qa_pairs (keyword, answer, is_active, priority, match_type, created_by)
VALUES ('營業時間', '我們的營業時間為週一至週五 09:00-18:00，週六 10:00-16:00，週日及例假日休息。', TRUE, 90, 'CONTAINS', NULL);

INSERT IGNORE INTO qa_pairs (keyword, answer, is_active, priority, match_type, created_by)
VALUES ('聯絡方式', '您可以透過以下方式聯絡我們：\n電話：02-1234-5678\nEmail：support@example.com', TRUE, 80, 'CONTAINS', NULL);
