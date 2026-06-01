# LINE Chatbot 推播平台

> 從 LINE OA 加好友、用戶分眾、訊息模板，到**百萬級高併發推播**、即時進度監控、點擊追蹤與 A/B 測試的完整後台系統。

![即時推播進度監控（Redis Stream + SSE）](docs/img/09-broadcast-detail-running.png)

> 📐 深入內容（資料模型、設計權衡、commit 歷史、本機開發流程）：[`docs/architecture.md`](./docs/architecture.md)

---

## 一、專案總覽

| | |
|---|---|
| **主題** | LINE OA（Official Account）後台推播管理平台 |
| **規模目標** | 百萬級用戶推播、高併發、可水平擴展 |
| **完成度** | 7 個階段核心功能 + 後續對 Push / Multicast 雙模式、成效統計準確性、用戶名單推播流程、效能與可靠度的多輪強化（單元測試 100+ 全綠） |
| **commit 結構** | 分階段、每次強化獨立 commit，可逐步用 `git log` 追蹤實作演進 |
| **核心特色** | Redis Stream 自管佇列、Redis Lua 速率限制、rate-aware 浮動 chunk size、SSE 即時進度推送、點擊計數 Redis buffer 避免 hot row、A/B 以點擊率比較、Push / Multicast 雙模式並存 |

---

## 二、技術棧

| 層 | 技術 |
|----|------|
| **後端** | Java 21 · Spring Boot 3.2 · Spring Security · JPA + Flyway · Redis（Stream / Pub-Sub / Lua）· LINE Bot SDK v9 |
| **前端** | React 18 · TypeScript · Vite · Ant Design 5 · Zustand · React Hook Form + Zod · Recharts |
| **資料庫** | MySQL / PostgreSQL 雙支援（透過 Spring Profile 切換） |
| **部署** | Docker · Docker Compose · multi-stage build |
| **測試** | JUnit 5 · Mockito · Spring Boot Test · H2 |

---

## 三、系統架構

### 3.1 整體分層

```mermaid
flowchart LR
    classDef line fill:#06C755,stroke:#03894C,color:#fff
    classDef ui fill:#4F8EF7,stroke:#2B5FCC,color:#fff
    classDef be fill:#6BAE65,stroke:#3F7639,color:#fff
    classDef redis fill:#DC382D,stroke:#8B1A14,color:#fff
    classDef db fill:#7E57C2,stroke:#4B2D85,color:#fff

    subgraph LINE["LINE 平台"]
      L1["LINE 用戶手機"]:::line
      L2["LINE Messaging API"]:::line
    end

    subgraph FE["管理後台（React）"]
      A1["推播管理頁"]:::ui
      A2["Flex 訊息編輯器"]:::ui
      A3["成效統計面板"]:::ui
    end

    subgraph BE["後端服務（Spring Boot）"]
      B1["Webhook 接收"]:::be
      B2["推播任務服務"]:::be
      B3["點擊追蹤"]:::be
      B4["Worker 執行池"]:::be
    end

    subgraph R["Redis 中介層"]
      R1["佇列（Stream）"]:::redis
      R2["限速桶（Lua）"]:::redis
      R3["即時計數"]:::redis
      R4["事件廣播（Pub/Sub）"]:::redis
    end

    subgraph DB["資料庫"]
      D1[("用戶 / 標籤")]:::db
      D2[("推播任務")]:::db
      D3[("點擊紀錄")]:::db
    end

    L1 -->|加好友 / 傳訊息| L2
    L2 -->|webhook 推送| B1
    B1 --> D1

    A1 -->|REST + SSE| B2
    A2 --> B2
    A3 --> B2

    B2 -->|切批次入列| R1
    B2 -->|計數累加| R3
    R1 -->|consumer group| B4
    B4 -->|取 token| R2
    B4 -->|呼叫 multicast / push| L2

    L1 -->|點訊息按鈕| B3
    B3 -->|302 轉跳| L1
    B3 --> D3

    R3 -->|進度事件| R4
    R4 -.->|SSE 推送| A3
```

### 3.2 單筆推播任務從建立到完成

```mermaid
sequenceDiagram
    participant U as 後台使用者
    participant API as Broadcast<br/>Controller
    participant S as Broadcast<br/>Service
    participant Q as Redis<br/>Stream
    participant W as Worker × N
    participant TB as 限速桶<br/>（Lua）
    participant L as LINE API
    participant C as 即時計數<br/>（Redis）
    participant SSE as SSE 推送

    U->>API: POST /broadcasts/:id/submit
    API->>S: 提交任務
    S->>S: 訊息內按鈕 URL 改寫為追蹤連結
    S->>S: 收件人切片（500 人 / 批）
    S->>C: 初始化計數器
    S->>Q: 全部批次推入佇列

    par 多個 Worker 並行消費
        W->>Q: 取一批
        W->>TB: 申請 token（不足則等待）
        TB-->>W: 核可
        W->>L: 呼叫 multicast / push
        L-->>W: X-Line-Request-Id
        W->>C: 計數 +N，並回報是否為最後一批
        C-->>SSE: 廣播即時進度
        SSE-->>U: 進度條動畫推送
    end

    Note over W,C: 失敗批次進入重試 sorted set，<br/>排程器每秒掃描、依退避策略重新入列
```

### 3.3 點擊追蹤流程

```mermaid
sequenceDiagram
    participant U as LINE 用戶
    participant CT as 點擊追蹤端點
    participant DB as 點擊紀錄
    participant T as 目標網站

    U->>CT: 點訊息按鈕，GET /c/{token}
    CT->>DB: 用 token 查原始網址
    CT-->>U: 302 轉跳到原始網址
    U->>T: 抵達目標網站
    par 背景非同步
        CT->>DB: 寫入點擊事件
        CT->>DB: 累加該連結的點擊次數
    end
```

---

## 四、功能與截圖

### 4.1 後台管理介面

### 登入

![登入頁](docs/img/01-login.png)

### Dashboard

![Dashboard](docs/img/02-dashboard.png)

收到訊息數、QA 命中率、AI 回覆量、近 7 天趨勢一站式呈現。下方額外展示「昨日 LINE multicast 累計送達」widget——LINE 對 multicast 的當日統計通常隔日才會 ready，因此 widget 預設查昨日；首次進入會自動拉取一次，之後完全靠右側「重新整理」按鈕觸發，避免無謂呼叫 LINE API。後端搭配 5 分鐘短期快取，狀態標籤呈現 ready / 尚未 ready / API 失敗。

### LINE 用戶管理

![LINE 用戶列表](docs/img/03-line-users.png)

- 清單同時顯示暱稱、截短的 LINE userId（含一鍵複製按鈕）、狀態與已貼標籤；支援關鍵字搜尋、狀態與標籤多條件篩選、批量貼標籤。
- 用戶加好友時觸發 LINE Follow Webhook，後端自動建檔；同時呼叫 LINE Profile API 補完暱稱、頭像、語系。
- 勾選多人後可一鍵「對所選人推播」，自動跳到建立推播頁並把名單帶入，同時鎖定 Push 模式以取得精準的每人送達結果。

### 標籤管理

![標籤管理](docs/img/04-tag-management.png)

每個標籤帶顏色與用戶數；新增 / 編輯介面含顏色選擇器。

### 訊息模板與 Flex 編輯器

![訊息模板列表](docs/img/05-message-templates.png)

![Flex 編輯器：左 JSON 右預覽](docs/img/06-flex-editor.png)

左側編輯 JSON、右側同步呈現 LINE 風格的訊息卡預覽，即打即現。

<br>

![預設模板選單](docs/img/07-preset-picker.png)

內建 5 種主題模板（新春問候、商品介紹、優惠券、活動公告、純文字），一鍵套用後可繼續微調。

### 建立推播

![建立推播流程](docs/img/14-broadcast-create-1.png)

可選擇訊息來源（套用模板或自訂 JSON）、目標類型（全部已加好友 / 依標籤 / 指定用戶 / Narrowcast）、API 模式（Push / Multicast）、排程時間，同時提供即時預覽。

<br>

![指定用戶名單的多選元件](docs/img/14-broadcast-create-2.png)

**指定用戶名單的整合**：

- 表單內嵌一個多選元件，輸入暱稱或 LINE userId 都能即時搜尋（自動延遲約 300 毫秒避免邊打字邊送請求；後端同時比對暱稱與 LINE userId 兩個欄位）。
- 也可以反向操作：先到「LINE 用戶」頁勾選多人，按下「對所選人推播」一鍵跳轉，系統自動把選好的名單帶進建立頁，並把 API 模式鎖定為 Push（少量名單通常需要精準的每人送達結果）。
- 已選的用戶會暫存在前端，即使你輸入新關鍵字搜尋，原本選好的不會被洗掉；操作手感類似 Slack 的 @ 提及選單。

<br>

![A/B 測試建立](docs/img/15-ab-test-create.png)

可定義多個版本（variant）與流量分配比例，預設 A/B 各 50%，可動態新增變體。

### 推播列表與進度

![推播管理列表](docs/img/08-broadcast-list.png)

列表用顏色標籤呈現任務狀態，進度條與成功 / 失敗計數一目了然；涵蓋草稿、已排程、執行中、已完成、失敗等全部狀態。

<br>

![推播詳情：執行中 + SSE 即時連線](docs/img/09-broadcast-detail-running.png)

頁面上方標示「即時連線中（SSE）」；進度條由後端透過 Redis Pub/Sub 推送，使用者不必手動重新整理。

<br>

![推播詳情：失敗任務](docs/img/10-broadcast-detail-failed.png)

![批次清單（可依狀態篩選）](docs/img/11-broadcast-detail-failed-list.png)

成效統計面板顯示成功率、平均嘗試次數、發送速率、耗時與錯誤分布長條圖（按「失敗人數」累計，並非批次數）。下方批次清單支援 SUCCESS / PARTIAL / FAILED 篩選並分頁。

<br>

![已完成詳情：進度與成效統計](docs/img/12-broadcast-detail-completed-1.png)

![已完成詳情：點擊追蹤與批次清單](docs/img/12-broadcast-detail-completed-2.png)

任務完成後額外呈現點擊率、總點擊、不重複 IP、各連結點擊數。

### A/B 比較

![A/B 比較頁](docs/img/13-ab-test-comparison.png)

各版本並排，依**點擊率**比較勝出版本，而非單純送達率——點擊率更能反映真實的行銷成效。

### LINE 串接設定

![LINE 串接設定](docs/img/16-line-settings.png)

Channel Secret 與 Access Token 以遮罩顯示（避免肩窺），「驗證連線」按鈕直接呼叫 LINE 的 bot info API 確認憑證有效。下方可編輯加入好友的歡迎訊息——使用者加好友時 Follow Webhook 處理完畢後，系統自動 push 此訊息。

### AI 串接設定

![AI 串接設定](docs/img/17-ai-settings.png)

以資料庫設定為主、`.env` 環境變數為備援；主開關可一鍵停用 AI（僅走 QA 規則回答）。

### QA 規則與用量監控

![問答管理](docs/img/18-qa-management.png)

![用量監控](docs/img/19-usage-monitor.png)

QA 規則支援完全相符 / 包含 / 正規表達式三種比對方式，啟用狀態與優先順序可即時調整。用量監控頁呈現訊息量趨勢、QA 命中率、AI 用量對比。

### 4.2 LINE 用戶端（手機畫面）

<img src="docs/img/20-line-follow-greeting.png" alt="加好友後收到歡迎訊息" width="320">

用戶加 OA 為好友後收到歡迎訊息——驗證 Follow Webhook → 後台建檔 → 自動推送歡迎訊息整條流程。

<br>

<img src="docs/img/21-line-text-broadcast.png" alt="收到純文字推播" width="320">

<br>

<img src="docs/img/22-line-flex-broadcast.png" alt="收到 Flex 訊息推播" width="320">

Flex 訊息採用商品介紹模板，hero 圖、價格、按鈕一站到位。

<br>

<img src="docs/img/23-line-qa-hit.png" alt="QA 規則命中回覆" width="320">

使用者傳訊息命中 QA 規則時的即時回覆。

<br>

<img src="docs/img/24-line-flex-click.png" alt="Flex 按鈕點擊跳轉" width="320">

點擊訊息按鈕跳轉的瞬間——後端先收 302 redirect 並非同步寫入點擊事件，使用者體感無延遲。

### 4.3 LINE Developers Console

![LINE Developers Console Channel 設定](docs/img/25-line-developers-console.png)

Channel 設定頁顯示本系統的 webhook URL（通過 ngrok 對外）已正確掛上、Verify 通過。

---

## 五、技術亮點

### 5.1 用 Redis Stream 取代外部訊息佇列

不依賴 Kafka / RabbitMQ，純 Redis 即可達成：

- **至少一次投遞（At-least-once）**：訊息在 ACK 前都會保留
- **Consumer Group**：N 個 worker 分散消費同一個 stream
- **PEL（Pending Entries List）**：worker 崩潰留下的訊息可被 `XCLAIM` 回收
- **死信處理**：排程每 60 秒掃 PEL，閒置過久的訊息重新派發
- **MAXLEN trim**：排程每 10 分鐘 approximate trim 到 ~100K entry，避免 ACK 過的訊息累積拖慢 PEL 掃描與重啟 load

```
Worker A ─┐
Worker B ─┼─► XREADGROUP broadcast-workers ─► broadcast:chunks:queue (Redis Stream)
Worker C ─┘                                          │
                                                      ▼
                                          自動分配給空閒 worker
```

### 5.2 Redis Lua Token Bucket 限速

自己寫 Lua 腳本在 Redis 端執行，補充 token 與消費 token 在同一個原子操作內完成，避免多個 worker 競爭：

```lua
local tokens = math.min(capacity, tokens + elapsed * refill_per_ms)
if tokens >= 1 then
    tokens = tokens - 1
    return 1  -- 核可
else
    return 0  -- 拒絕
end
```

預設 push 500 req/sec、multicast 50 batches/sec（取 LINE 官方上限的 25% 安全 buffer：push 2000/sec、multicast 200 batches/sec），跨多個應用實例共享同一份配額。實際上線可依 OA 方案 quota 與 traffic pattern 上下調整。

### 5.3 原子終止判定

多 worker 並行，**只有「最後一個」完成的 worker 觸發任務收尾**。靠 Lua 把計數累加與終點判定包成同一個原子操作：

```lua
local newSent = redis.call('INCRBY', sentKey, sentDelta)
local total = tonumber(redis.call('GET', totalKey))
return newSent >= total and 1 or 0
```

只有拿到回傳 1 的 worker 會呼叫 `finalizeTask()`，確保收尾邏輯只跑一次。

### 5.4 避免「熱列」競爭（Hot Row）

兩處最容易撞行鎖的計數欄位（`broadcast_tasks.sent_count` 與 `click_links.click_count`）都不直接被高頻事件更新，改走 **Redis INCR 累積 + 排程批次回寫** 模式：

```
事件發生 → Redis INCR（低延遲、無鎖競爭）+ 標記 dirty
            ↓
排程器每 5 秒讀 dirty set、Lua 原子 GET+SET=0 取 delta
            ↓
單一 UPDATE += delta（一個 row 一次寫完，不是每筆事件寫一次）
```

具體案例：一則百萬人推播附 tracking link、1 小時內 30 萬人點擊：

- **原本**：30 萬次 `UPDATE click_count = click_count + 1` 撞同一 row、InnoDB 行鎖序列化 → 排隊 25 分鐘
- **改後**：DB 寫入降到每 5 秒 1 次 UPDATE += 累積值 → 寫入頻率降 400 倍、行鎖瞬間結束

讀路徑同步處理：UI 查詢 `click_count` 時把 DB 存量加上 Redis 未 flush 的 delta，數字永遠是當下值、不會落後排程週期。

### 5.5 SSE + Redis Pub/Sub 廣播訂閱

進度即時推送不用 WebSocket（單向廣播用 SSE 更輕量）。後端用**模式訂閱**（pattern subscription）讓單一監聽器涵蓋所有任務：

```java
listenerContainer.addMessageListener(listener,
    new PatternTopic("broadcast:progress:*"));
```

**多實例正確性**：A 實例 publish → B 實例（前端 SSE 連的那台）的監聽器也會收到 → 推給瀏覽器。前端不需要 sticky session 綁定特定後端。

### 5.6 LINE retry key 設計

```java
UUID retryKey = UUID.nameUUIDFromBytes(
    ("broadcast-" + chunkId + "-" + attempts).getBytes()
);
```

- 同批次同嘗試次數 → 同一個 retry key → LINE 視為重試，**不重複扣訊息配額**
- 新一次嘗試 → 新 retry key → LINE 視為新請求

### 5.7 用 Sorted Set 做退避重試，避免阻塞 worker

```
批次失敗 → ZADD broadcast:retry:zset, score=下次重試時間戳, member=批次ID
            ↓
排程器每秒掃 ZRANGEBYSCORE 0 now → 撈到期的批次重新入列
            ↓
退避策略：1 秒 → 5 秒 → 30 秒 → 2 分（最多 4 次）
```

worker 不會被失敗的批次卡住；任務取消時 `ZREM` 即可中止所有未到期的重試。

### 5.8 點擊追蹤的 URL 改寫

任務提交時，後端用 Jackson 遞迴掃過 messages JSON，把所有 `{type:"uri", uri:"..."}` 改寫為 `/c/{token}`，原網址寫入 `click_links` 表：

```
[原始] {"type":"button","action":{"type":"uri","uri":"https://example.com/sale"}}
                                  ↓ 改寫
[新版] {"type":"button","action":{"type":"uri","uri":"https://yourdomain/c/aBc3kQ8"}}
                                  ↓ 用戶點擊
GET /c/aBc3kQ8 → 非同步寫入點擊事件 → 302 轉跳到 https://example.com/sale
```

- LINE 自家連結（`line.me` 開頭）跳過改寫，避免破壞 LINE 內部 deeplink
- 點擊事件採非同步寫入，redirect 體驗維持毫秒級
- A/B 測試以**點擊率**比較取代純送達率，更能反映行銷成效

### 5.9 Narrowcast 與 Multicast 雙路分流

同一個 `BroadcastTask` 資料模型，`submit()` 依目標類型分流：

| 比較項 | Multicast（後端自管） | Narrowcast（交給 LINE 自管） |
|--|---|---|
| 批次切分 | 後端自己切 500 人/批 | 由 LINE 平台自管 |
| 進度追蹤 | 後端紀錄每批次成敗 | 用 LINE `getNarrowcastProgress` 輪詢 |
| 適用場景 | <100K 人 + 需精準追蹤 | >100K 人 + 廣播性質 |

### 5.10 Push / Multicast 雙模式 + 4xx 偵測

每個推播任務可選 API 模式（預設 Push）；批次處理器依模式分流：

- **Push 模式**：迭代批次內每位用戶，逐一呼叫 `pushMessage`；每人取自己的 retry key，能拿到每位用戶的 200 / 4xx 結果
- **Multicast 模式**：一次 `multicastMessage(500 人)`，整批 200 / 失敗

4xx 偵測曾經用字串比對「`" 400 "`」（兩邊空格），但 LINE SDK v9 實際丟出的例外訊息是 `MessagingApiClientException: API returns error: code=400`，比對不到 → 4xx 被誤判成「致命錯誤」→ 整個批次重試 → 真實用戶收到 N 份重複訊息。改為用例外型別判斷直接讀 HTTP code：

```java
if (t instanceof AbstractLineClientException lex) {
    int code = lex.getCode();
    if (code >= 400 && code < 500) return true; // 用戶級失敗、不重試
}
```

### 5.11 PUSH chunk size 隨 rate 連動（rate-aware）

切片大小不寫死、改成跟 rate limit config 連動：

```
PUSH chunk size = rate × 目標 worker 持有時間（2 秒）
```

| 場景 | rate config | chunk size | 1M 用戶總時間 | worker 鎖 chunk |
|------|-----------|-----------|-------------|---------------|
| 保守 | 5/sec | 50（夾下限）| 55 小時 | 10 秒 |
| 中等（目前 default）| 500/sec | 1000 | 33 分鐘 | 2 秒 |
| 拉滿 LINE 上限 | 2000/sec | 4000 | 8.3 分鐘 | 2 秒 |

關鍵設計：**worker 鎖 chunk 的時間恆定 ~2 秒**，rate 升降時 chunk 自動跟著走、不會固定 500 人造成 partial failure 反應太粗、也不會固定 50 人造成 DB row 爆炸。Multicast 模式繼續用 500（LINE API 硬上限）。

### 5.12 Multicast 成效統計的誠實設計

LINE 對 multicast 的當日累計**通常隔天才 ready**，而且這個累計是「全日合計」，無法精準歸因到單一任務。原本想做「每任務的當日增量」（本次 total − 上次 last_total），實作後發現：先結算的任務會吃光當日增量、後續任務拿到 0，**完全沒有歸因意義**——所以撤回此設計。

修正後的呈現方式：

| 模式 | 任務詳情頁進度卡 | 成效統計面板 |
|---|---|---|
| **Push** | 顯示收件人 / 已送 / 成功 / 失敗（每人精準計數） | 完整 stats，含送達人數、成功率、發送速率、進行中批次提示 |
| **Multicast** | 只顯示一行任務狀態（成功 / 失敗 / 進行中）+ 引導去 Dashboard 看當日累計 | 改為提示卡：「無 per-user 統計，實際送達請看 Dashboard」 |

新表 `multicast_daily_delivery` 改為 Dashboard widget 的快取（短期 5 分鐘 TTL），LINE 回 「尚未 ready」時不寫入快取。

### 5.13 角色權限分層（RBAC）

5 個角色由高到低：

```
ADMIN > MANAGER > MARKETER > CS_AGENT > VIEWER
```

- **後端**：`@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")` 標註敏感端點
- **前端**：`usePermissions` 自製 hook 依角色隱藏按鈕
- **稽核**：`CurrentUserService` 從安全脈絡取出當前使用者，自動寫入 `created_by`

---

## 六、開發階段與里程碑

| 階段 | 主題 | 核心技術 |
|-------|------|---------|
| 0 | 專案地基與 LINE 串接 | Spring Boot 骨架、Flyway 雙資料庫、JWT 登入、QA 規則引擎、Webhook 接收與簽章驗證 |
| 1 | LINE 用戶與標籤 | Webhook follow 事件、多對多標籤關聯 |
| 2 | 模板與基本推播 | multicast 500/批、冪等鍵 |
| 3 | **高併發核心** | Redis Stream + Worker Pool + 限速桶 + 退避重試 |
| 4 | 進度與成效 | SSE + Pub/Sub、統計、PEL 死信處理 |
| 5 | Flex 編輯器 | JSON 編輯 + 即時預覽、預設模板、匯入 / 匯出 |
| 6 | 加分項全集 | 排程、權限分層、Narrowcast、A/B 測試 |
| 7 | 點擊追蹤 | URL 改寫、點擊率統計、A/B 用點擊率比較 |
| 8 | AI 串接整合 | OpenAI / Gemini 抽象介面、後台設定、可一鍵停用 |
| 9 | Push / Multicast 雙模式 | apiMode 雙路分流、per-user retry key、Dashboard 顯示 LINE 平台日送達 |
| 10 | 推播 UX 優化 | 指定用戶推播整合、批次清單篩選分頁、Flex Simulator 匯入助手、UI 中文化 |
| 11 | 加好友歡迎訊息 | 後台可編輯文案、Follow 事件自動推送 |
| 12 | 穩定性與可靠度修補 | retry key 設計強化、tracking URL 反向解包、各路 race condition 修正、單元測試補完 |
| 13 | 效能與可靠度強化 | rate config 對齊 LINE 真實上限、PUSH chunk size rate-aware 浮動、click_count Redis buffer 解 hot row、Stream 排程 trim |

每個階段對應獨立的 commit，主題清楚分離。

>  📐 完整 commit 歷史、設計權衡、本機開發步驟、已知限制等深入內容，請見 [`docs/architecture.md`](./docs/architecture.md)。
