-- AI 串接設定（單例，固定只有一筆 id=1）
CREATE TABLE IF NOT EXISTS ai_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    provider    VARCHAR(20)  NOT NULL DEFAULT 'openai',
    api_key     VARCHAR(255),
    base_url    VARCHAR(255),
    model       VARCHAR(100),
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
