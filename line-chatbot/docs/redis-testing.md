# Redis 測試指南

本文件說明此專案中 Redis 的所有測試方式。

---

## Redis 在本專案的用途

| 功能 | Key 格式 | TTL | 相關類別 |
|------|----------|-----|---------|
| QA 快取 | `qa:list` | 1 小時 | `QAService` |
| Rate Limit | `rate:{lineUserId}` | 1 分鐘 | `LineMessageService` |
| JWT 黑名單 | `jwt:blacklist:{jti}` | 與 JWT 剩餘時間相同 | `JwtTokenProvider` |

---

## 方法一：單元測試（Mock Redis，不需真實連線）

使用 Mockito mock 掉 `RedisTemplate`，完全不需要 Redis 服務。

**已有測試檔**：`backend/src/test/java/com/linechatbot/service/QAServiceTest.java`

```bash
cd backend
mvn test -Dtest=QAServiceTest
```

**測試內容**：
- QA Cache Miss 時向 DB 查詢
- 新增/刪除 QA 後呼叫 `redisTemplate.delete("qa:list")`
- EXACT / CONTAINS / REGEX 比對邏輯

**適用場景**：驗證業務邏輯，不驗證 Redis 實際行為。

---

## 方法二：整合測試（docker-compose Redis，真實 Redis）

使用 `docker-compose up redis -d` 啟動的 Redis，測試實際 Redis 操作。

**前置條件**：先啟動 Redis。

```bash
docker-compose up redis -d
```

**測試檔案**：

| 測試類別 | 測試目標 |
|---------|---------|
| `QACacheIntegrationTest` | QA 列表 Cache 寫入、命中、過期清除 |
| `RateLimitIntegrationTest` | INCR + EXPIRE Rate Limit 機制 |
| `JwtBlacklistIntegrationTest` | 登出 Token 加入黑名單、驗證失敗 |

```bash
# 執行所有整合測試（需要 Docker）
cd backend
mvn test -Dtest="QACacheIntegrationTest,RateLimitIntegrationTest,JwtBlacklistIntegrationTest"

# 執行全部測試
mvn test
```

---

## 方法三：本地手動測試（docker-compose + redis-cli）

### 啟動 Redis

```bash
# 啟動 Redis（已在 docker-compose.yml 定義）
docker-compose up redis -d
```

### 連進 redis-cli

```bash
docker exec -it $(docker ps -qf "ancestor=redis:7-alpine") redis-cli
```

### 常用指令

```bash
# 查看所有 key
KEYS *

# QA 快取
GET qa:list
TTL qa:list             # 查看剩餘秒數（-1 表示永不過期，-2 表示不存在）

# Rate Limit
GET rate:U1234567890    # 查看某 user 的請求計數
TTL rate:U1234567890    # 查看視窗剩餘時間

# JWT 黑名單
KEYS jwt:blacklist:*    # 列出所有黑名單 token
TTL jwt:blacklist:<jti> # 查看黑名單剩餘時間

# 清除所有 key（測試用）
FLUSHDB
```

### 手動測試 QA Cache 流程

```bash
# 1. 確認 Cache 不存在
EXISTS qa:list          # 回傳 0

# 2. 透過 API 觸發查詢（Cache Miss，寫入 Redis）
curl -H "Authorization: Bearer <JWT>" http://localhost:8080/api/qa

# 3. 確認 Cache 已建立
EXISTS qa:list          # 回傳 1
TTL qa:list             # 應在 3600 附近

# 4. 新增或修改 QA 後（Cache 應被清除）
curl -X POST -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"keyword":"test","answer":"test","matchType":"EXACT"}' \
  http://localhost:8080/api/qa

EXISTS qa:list          # 回傳 0（已清除）
```

### 手動測試 Rate Limit

```bash
# 模擬 Rate Limit 觸發（超過 100 次/分鐘）
# 在 redis-cli 中手動設置計數超過限制
SET rate:U1234567890 101
EXPIRE rate:U1234567890 60

# 此後該 user 的下一筆訊息應收到「您的訊息太頻繁」回應
```

### 手動測試 JWT 黑名單

```bash
# 1. 登入取得 JWT
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your_password"}'

# 2. 登出（Token 加入黑名單）
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <JWT>"

# 3. 確認黑名單 key 存在
KEYS jwt:blacklist:*    # 應看到一個 key

# 4. 用舊 Token 再次請求，應回傳 401
curl -H "Authorization: Bearer <JWT>" http://localhost:8080/api/qa
```

---

## 方法四：完整 Docker 環境測試

使用 docker-compose 啟動全部服務（含後端），進行端對端測試。

```bash
# 複製環境變數
cp .env.example .env
# 編輯 .env 填入必要設定

# 啟動所有服務
docker-compose up -d

# 查看後端 log（含 Redis 操作紀錄）
docker-compose logs -f backend

# 停止並清除
docker-compose down -v
```

---

## 各測試方法比較

| 方法 | 需要 Docker | 測試速度 | 測試真實性 | 適用情境 |
|------|------------|---------|-----------|---------|
| 單元測試（Mock） | 不需要 | 最快 | 低（Mock） | CI/CD、邏輯驗證 |
| 整合測試（Testcontainers） | 需要 | 中（啟動容器約 5 秒） | 高（真實 Redis） | 驗證 Redis 行為 |
| 手動（redis-cli） | 需要 | 手動 | 高 | 除錯、探索 |
| Docker 完整環境 | 需要 | 慢 | 最高（端對端） | 上線前驗收 |
