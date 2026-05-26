-- AI 串接設定（單例，固定只有一筆 id=1）
CREATE TABLE IF NOT EXISTS ai_config (
    id          BIGSERIAL PRIMARY KEY,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    provider    VARCHAR(20)  NOT NULL DEFAULT 'openai',
    api_key     VARCHAR(255),
    base_url    VARCHAR(255),
    model       VARCHAR(100),
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_ai_config_updated_at BEFORE UPDATE ON ai_config
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
