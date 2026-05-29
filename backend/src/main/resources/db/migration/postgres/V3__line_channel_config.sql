-- LINE Messaging API 頻道設定（單例，固定只有一筆）
CREATE TABLE IF NOT EXISTS line_channel_config (
    id                      BIGSERIAL PRIMARY KEY,
    channel_id              VARCHAR(50),
    channel_secret          VARCHAR(255),
    channel_access_token    VARCHAR(512),
    server_base_url         VARCHAR(255),
    webhook_enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    auto_reply_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    greeting_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
