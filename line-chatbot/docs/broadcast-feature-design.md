# LINE 推播功能設計文件

> 本文件描述 LINE Chatbot 後台推播功能的整體設計，目標是支援百萬級用戶推播，並作為展示分散式任務處理、限流、即時監控等技術能力的 side project 模組。
>
> 撰寫日期：2026-05-25

---

## 一、設計目標與技術亮點

| 目標 | 對應技術 | 展示能力 |
|------|----------|----------|
| 百萬級用戶推播 | 任務分片 + Worker Pool + Redis Queue | 分散式任務處理 |
| 順應 LINE Rate Limit | Token Bucket（Bucket4j + Redis）+ 指數退避 | 限流與重試策略 |
| 即時進度監控 | Server-Sent Events（SSE）+ Redis Pub/Sub | 即時通訊（比 WebSocket 輕量） |
| 高可用與不漏推 | 任務狀態機 + 冪等鍵 + 斷點續推 | 分散式系統設計 |
| 標籤化分眾 | 多對多關聯 + 物化視圖快取 + LINE Audience API | 資料模型與 CDN 思維 |

---

## 二、LINE API 限制與選型

| API | 限制 | 適用情境 | 結果可追蹤性 |
|-----|------|----------|------------|
| `pushMessage` | 100 req/min 對單一 userId | 測試發送、客服個別回應 | per-user |
| `multicast` | 500 users/call、每分鐘配額 | **批次推播主力** | batch-level（整批成敗） |
| `narrowcast` | 大型 audience（>50k）切分 | 全體 / 條件 audience | 僅統計摘要 |
| `broadcast` | 60 req/min | 全用戶廣播 | 僅統計摘要 |

**選型結論：**

- **預設使用 `multicast`（500 人/批）自管分批** — 可記錄每批送達狀態、可中途暫停 / 續推。
- **>100k 且全用戶的場景才用 `narrowcast`** — 走 LINE Audience API。
- **「測試發送」用 `pushMessage`** — 後台用戶可預覽。

---

## 三、資料表設計（V4 migration）

```sql
-- 用戶資料表（從 LINE 加好友時建立 / Webhook follow event 觸發）
line_users
├── id BIGINT PK
├── line_user_id VARCHAR(100) UNIQUE      -- LINE 用戶 ID
├── display_name VARCHAR(100)             -- 從 Profile API 取得
├── picture_url VARCHAR(500)
├── status VARCHAR(20)                    -- FOLLOWED / BLOCKED
├── followed_at DATETIME
├── unfollowed_at DATETIME
├── created_at, updated_at
└── INDEX(status), INDEX(followed_at)

-- 標籤
tags
├── id BIGINT PK
├── name VARCHAR(50) UNIQUE               -- 例：VIP、新客、北部
├── color VARCHAR(7)                      -- HEX 色碼，前端視覺辨識
├── description VARCHAR(200)
├── user_count INT                        -- 反正規化，避免 COUNT(*) 全掃
├── created_by BIGINT FK users
└── created_at, updated_at

-- 用戶 ↔ 標籤（多對多，預期數據量大）
user_tags
├── line_user_id BIGINT FK line_users
├── tag_id BIGINT FK tags
├── tagged_at DATETIME
├── tagged_by BIGINT FK users
└── PRIMARY KEY (line_user_id, tag_id)
    INDEX(tag_id, line_user_id)           -- 反向查詢

-- 訊息模板（可重用的 Flex / Text 模板）
message_templates
├── id BIGINT PK
├── name VARCHAR(100)
├── message_type VARCHAR(20)              -- TEXT / FLEX / IMAGE / TEMPLATE
├── content JSON                          -- LINE Messages 物件陣列
├── thumbnail VARCHAR(500)                -- 預覽縮圖
├── created_by BIGINT FK users
└── created_at, updated_at

-- 推播任務（每次推播一筆）
broadcast_tasks
├── id BIGINT PK
├── name VARCHAR(200)
├── message_content JSON                  -- 實際送出的 messages（snapshot，避免模板被改）
├── target_type VARCHAR(20)               -- ALL / TAGS / USER_LIST / NARROWCAST
├── target_filter JSON                    -- {tag_ids:[1,2], match:"ANY|ALL"}
├── status VARCHAR(20)                    -- DRAFT / SCHEDULED / QUEUED / RUNNING
│                                            / PAUSED / COMPLETED / FAILED / CANCELLED
├── total_recipients INT                  -- 預估 / 鎖定的收件人數
├── sent_count INT DEFAULT 0
├── success_count INT DEFAULT 0
├── failed_count INT DEFAULT 0
├── scheduled_at DATETIME NULL            -- 排程發送時間
├── started_at, finished_at DATETIME NULL
├── idempotency_key VARCHAR(64) UNIQUE    -- 防重複建立
├── created_by BIGINT FK users
└── created_at, updated_at
    INDEX(status, scheduled_at)

-- 推播分片（500 人/片，給 multicast 用）
broadcast_chunks
├── id BIGINT PK
├── task_id BIGINT FK broadcast_tasks
├── chunk_index INT                       -- 第幾片
├── recipient_ids JSON                    -- 該片的 line_user_id 清單
├── status VARCHAR(20)                    -- PENDING / SENDING / SUCCESS / FAILED / RETRYING
├── attempts INT DEFAULT 0
├── line_request_id VARCHAR(100)          -- LINE API 回傳的 X-Line-Request-Id（追蹤用）
├── error_code VARCHAR(50)
├── error_message TEXT
├── sent_at DATETIME NULL
└── INDEX(task_id, status), INDEX(status, attempts)

-- 個別收件人結果（用於精細追蹤；可選，視規模決定是否啟用）
-- 對 100 萬用戶 × 50 次推播 = 5000 萬筆，需考慮分區或冷數據歸檔
broadcast_recipients
├── id BIGINT PK
├── task_id BIGINT FK
├── chunk_id BIGINT FK
├── line_user_id VARCHAR(100)
├── status VARCHAR(20)                    -- DELIVERED / FAILED / SKIPPED
├── error_code VARCHAR(50) NULL
└── INDEX(task_id, status), INDEX(line_user_id, task_id)
```

> **資料量考量：** `broadcast_recipients` 若預期超千萬，可改用 ClickHouse / 月分表，或改成「只記錄失敗者」。MVP 階段保留全量即可。

---

## 四、核心架構流程

```
┌─────────────┐                                          ┌──────────────┐
│  後台前端   │ 1.建立任務 (HTTP)                        │ LINE Platform │
│ (React)     ├─────────────┐                            └──────▲───────┘
│             │             ▼                                   │
│             │      ┌─────────────────┐                        │
│             │      │BroadcastService │  3.推到 Redis Queue    │
│             │      │ - 計算 audience │                        │
│             │      │ - 切分 500/批   │                        │
│             │      │ - 建立 chunks   │                        │
│             │      └────────┬────────┘                        │
│             │               │                                 │
│             │ 2.SSE 連線    │                                 │
│             │◄──────┐       │  ┌──────────────────┐           │
│             │       │       └─►│ Redis Stream:    │           │
└─────────────┘       │          │ broadcast:chunks │           │
                      │          └────────┬─────────┘           │
                      │                   │ 4.consume           │
              ┌───────┴─────────┐         ▼                     │
              │ProgressService  │ ┌──────────────────────┐      │
              │ (SSE Emitter)   │ │ BroadcastWorker Pool │      │
              │ - Redis Pub/Sub │ │ - Token Bucket 限速  │      │
              │                 │ │ - multicast API      ├──────┘
              │                 │ │ - 失敗指數退避重試   │
              └──────▲──────────┘ └──────────┬───────────┘
                     │ 5.publish 進度        │
                     └───────────────────────┘
```

**任務狀態機：**

```
DRAFT ──submit──► QUEUED ──pickup──► RUNNING ──finish──► COMPLETED
  │                                   │  ▲
  │                                   │  └─resume── PAUSED
  └──schedule──► SCHEDULED ──fire────┘                │
                                                      └─pause
                                       FAILED ◄─exceed_retries
                                       CANCELLED ◄─user_cancel
```

---

## 五、後端模組設計

### 套件結構新增

```
com.linechatbot/
├── controller/
│   ├── BroadcastController.java          # 任務 CRUD + 進度 SSE
│   ├── MessageTemplateController.java    # 模板 CRUD + Flex 預覽
│   ├── TagController.java                # 標籤 CRUD
│   └── LineUserController.java           # 用戶清單 + 標籤指派
├── service/
│   ├── BroadcastService.java             # 任務管理：建立、暫停、取消
│   ├── BroadcastDispatchService.java     # 切片、推入 queue
│   ├── BroadcastWorker.java              # 消費 chunk、呼叫 LINE API
│   ├── BroadcastProgressService.java     # SSE 與 Redis Pub/Sub
│   ├── MessageTemplateService.java
│   ├── TagService.java
│   ├── LineUserService.java              # 用戶資料維護（從 Webhook follow event 更新）
│   └── ratelimit/
│       └── LineApiRateLimiter.java       # Bucket4j 包裝
├── model/
│   ├── entity/
│   │   ├── LineUser.java
│   │   ├── Tag.java
│   │   ├── UserTag.java
│   │   ├── MessageTemplate.java
│   │   ├── BroadcastTask.java
│   │   ├── BroadcastChunk.java
│   │   └── BroadcastRecipient.java
│   └── dto/
│       ├── BroadcastTaskDTO.java
│       ├── BroadcastCreateRequest.java
│       ├── BroadcastProgressEvent.java   # SSE payload
│       ├── FlexMessageDTO.java
│       └── TagDTO.java
└── config/
    ├── BroadcastConfig.java              # 第二個 ThreadPool: broadcastWorkerExecutor
    └── RateLimiterConfig.java
```

### 關鍵實作要點

**1. Token Bucket 限速（`LineApiRateLimiter`）**

- 用 Bucket4j 提供 multicast 配額：例如 60 calls/min（保守，比 LINE 給的少一點留 buffer）。
- Redis-backed bucket → 多實例部署也能共享配額。
- Worker 取 chunk 前先 `tryConsume()`，失敗就 sleep 並重試。

**2. Worker 執行緒池（與 Webhook 池隔離）**

```java
@Bean("broadcastWorkerExecutor")
public Executor broadcastWorkerExecutor() {
    // core=8, max=32, queue=200
    // 隔離 webhook 池：避免推播塞滿後影響即時回覆
}
```

**3. Redis Stream 作為任務佇列**

- Key: `broadcast:chunks:queue`
- 用 Consumer Group 分散到多個 Worker 實例。
- 訊息含：`{chunkId}`，Worker 從 DB 取細節（保持 Stream 訊息小）。
- 失敗的 chunk 不 ACK → 自動重新進入待消費。

**4. 失敗重試策略**

- chunk 級別重試：1s → 5s → 30s → 2min（最多 4 次）。
- 429（rate limited）：吃完當前 token bucket、進入較長 backoff。
- 5xx：可重試；4xx（除 429）：直接標記失敗。
- 達上限後寫入 `broadcast_chunks.status = FAILED`，整個 task 結束時若有失敗 chunk 標記 task 為「部分失敗」。

**5. 冪等性**

- 建立任務時前端帶 `idempotency_key`（UUID），同 key 24h 內回傳同一個 task。
- chunk 重試時不會重複扣 LINE 配額（依 LINE 的 `X-Line-Retry-Key` header）。

**6. 進度更新（避免 DB hot row）**

- Worker 完成 chunk 後：
  1. 更新 chunk 狀態到 DB
  2. `INCR broadcast:task:{id}:sent_count` (Redis)
  3. `PUBLISH broadcast:progress:{id}` (Redis Pub/Sub)
- 每 5 秒或每 100 個 chunks 才把 Redis 累計同步回 task 表（批次更新）。
- task 完成時做最後一次完整同步。

**7. SSE 進度推送**

- `GET /api/broadcast/{id}/progress/stream` 回傳 `text/event-stream`。
- Server 端訂閱 `broadcast:progress:{id}` Redis channel。
- 多後台用戶同時看同一任務也 OK（Redis Pub/Sub 廣播）。

---

## 六、API 設計

```
# 標籤管理
GET    /api/tags                      # 標籤列表（含用戶數）
POST   /api/tags                      # 新增標籤
PUT    /api/tags/{id}
DELETE /api/tags/{id}

# 用戶管理
GET    /api/line-users                # 分頁查詢，支援標籤、暱稱搜尋
POST   /api/line-users/{id}/tags      # 指派標籤 { tagIds: [1,2] }
POST   /api/line-users/bulk-tag       # 批量貼標籤 { userIds, tagIds, action: ADD|REMOVE }
GET    /api/line-users/by-tag/{tagId} # 依標籤查用戶

# 訊息模板
GET    /api/message-templates
POST   /api/message-templates
PUT    /api/message-templates/{id}
DELETE /api/message-templates/{id}
POST   /api/message-templates/{id}/preview   # 回傳 LINE Flex Simulator URL 或圖片

# 推播任務
GET    /api/broadcasts                # 任務列表（支援狀態篩選）
GET    /api/broadcasts/{id}
POST   /api/broadcasts                # 建立任務 (DRAFT)
POST   /api/broadcasts/{id}/estimate  # 估算收件人數(不真的發送)
POST   /api/broadcasts/{id}/test      # 測試發送給指定 userId（用 pushMessage）
POST   /api/broadcasts/{id}/submit    # 提交執行（or 排程）
POST   /api/broadcasts/{id}/pause
POST   /api/broadcasts/{id}/resume
POST   /api/broadcasts/{id}/cancel

# 進度與成效
GET    /api/broadcasts/{id}/progress         # 一次性查詢
GET    /api/broadcasts/{id}/progress/stream  # SSE 即時推送
GET    /api/broadcasts/{id}/statistics       # 成效摘要
GET    /api/broadcasts/{id}/failures         # 失敗清單（含原因）
```

---

## 七、前端設計

### 新增頁面與元件

```
pages/
├── BroadcastList.tsx              # 推播任務列表（含狀態、進度條）
├── BroadcastCreate.tsx            # 建立推播（多步驟 wizard）
├── BroadcastDetail.tsx            # 任務詳情（即時進度 + 成效）
├── MessageTemplates.tsx           # 訊息模板管理
├── TagManagement.tsx              # 標籤 CRUD
└── LineUsers.tsx                  # LINE 用戶清單 + 標籤指派

components/
├── Broadcast/
│   ├── BroadcastWizard.tsx        # 4 步驟：訊息 → 對象 → 排程 → 確認
│   ├── ProgressMonitor.tsx        # SSE 連線、即時進度條
│   ├── StatisticsPanel.tsx        # 成效圖表
│   └── FailureTable.tsx
├── FlexEditor/
│   ├── FlexEditor.tsx             # Flex Message 視覺化編輯器
│   ├── FlexPreview.tsx            # LINE 風格預覽
│   └── JsonModeEditor.tsx         # 進階：JSON 直接編輯
└── Tags/
    ├── TagPicker.tsx               # 多選標籤
    └── TagChip.tsx                 # 帶顏色的小標籤
```

### Flex Message 編輯器策略

- **MVP 階段：** 提供 textarea 貼 JSON + 即時預覽 + LINE Flex Simulator 連結。
- **進階：** 用 LINE Flex Message Simulator 風格的拖拉式編輯器（含 Bubble / Carousel / Box / Text / Image / Button）。
- 預設模板庫：問候、商品介紹、優惠券、活動公告 4 種起跳。

### 即時進度監控頁面

```typescript
// useBroadcastProgress.ts
function useBroadcastProgress(taskId: number) {
  const [progress, setProgress] = useState<Progress>()
  useEffect(() => {
    const sse = new EventSource(`/api/broadcasts/${taskId}/progress/stream`)
    sse.onmessage = (e) => setProgress(JSON.parse(e.data))
    return () => sse.close()
  }, [taskId])
  return progress
}
```

UI 顯示：

- 大進度條（已送 / 總數，% 與時間估算）
- 即時計數：成功 / 失敗 / 重試中
- 速率折線圖（每秒送出量，看是否被限速）
- 失敗 chunk 即時列表
- 暫停 / 取消 / 續推按鈕

---

## 八、實作分階段建議

| 階段 | 範圍 | 目標 |
|------|------|------|
| **Phase 1 — 基礎建設** | LineUser 資料表、follow webhook 自動建檔、Tag CRUD、用戶貼標籤 | 有用戶資料才能推播 |
| **Phase 2 — 模板與簡單推播** | MessageTemplate、文字推播、multicast 批次 | 跑通主流程 |
| **Phase 3 — 高併發核心** | Redis Stream Queue、Worker Pool、Token Bucket、重試 | 真正的高併發架構 |
| **Phase 4 — 進度與成效** | SSE、Redis Pub/Sub、Statistics API、Failure 列表 | 監控閉環 |
| **Phase 5 — Flex 編輯器** | Flex JSON 編輯器、預覽、模板庫 | 完整 UX |
| **Phase 6（選做）** | Narrowcast、A/B 測試、排程推播、定期推播 | 加分項 |

---

## 九、面試展示時可強調的點

1. **任務分片 + 限流 + 重試** — 不是 for-loop 串 API，而是真正的分散式任務處理。
2. **狀態機設計** — 暫停、續推、冪等性、斷點續推。
3. **SSE 而非 WebSocket** — 推播進度是單向廣播，SSE 更輕量；用 Redis Pub/Sub 實現多後端實例。
4. **Redis 應用層次** — 不只 cache，還用了 Stream（queue）、Pub/Sub（事件）、原子計數（Hot row 避免）。
5. **資料模型分層** — Task / Chunk / Recipient 三層，依規模選擇查詢粒度。
6. **執行緒池隔離** — Webhook 池 vs Broadcast Worker 池，避免推播塞爆即時回覆。
7. **與 LINE API 限制協作** — 不是硬幹，而是設計成跟著官方限制走。
