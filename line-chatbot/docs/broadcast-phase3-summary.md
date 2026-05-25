# Phase 3 完成總結 — 高併發核心

> 完成日期：2026-05-25
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 上一階段：[Phase 2 — 模板與簡單推播](./broadcast-phase2-summary.md)
>
> 範圍：Redis Stream queue、多 Worker、Token Bucket 限速、指數退避重試、Redis 計數避免 hot row

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| Redis Stream 作為 chunk queue | ✅ | Consumer group `broadcast-workers`、at-least-once 投遞 |
| 多 Worker 並行消費 | ✅ | `BroadcastWorkerManager` 啟動 N 個 worker（預設 4） |
| Token Bucket 共享限速 | ✅ | Redis Lua 腳本實作，跨實例原子操作 |
| 失敗 chunk 指數退避重試 | ✅ | 1s → 5s → 30s → 2min，最多 4 次 |
| Redis 計數 + 定期 flush | ✅ | INCR 累計、每 5s 同步回 DB；避免 hot row |
| 原子終止判斷 | ✅ | Lua 內判定 `sent >= total`，僅一個 worker 觸發 finalize |
| LINE Retry Key 跨重試穩定 | ✅ | 沿用 Phase 2 設計 |

---

## 二、新增與修改檔案

### 後端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Migration | `mysql/V6__broadcast_phase3.sql` | 新增 |
| Migration | `postgres/V6__broadcast_phase3.sql` | 新增 |
| Entity | `BroadcastChunk.java` | 新增 `nextRetryAt`、`lastAttemptAt`、`maxAttempts` 欄位 |
| Config | `BroadcastConfig.java` | 加上 `@EnableScheduling`（給 RetryScheduler / CounterFlusher） |
| Service | `service/ratelimit/LineApiRateLimiter.java` | 新增（Lua token bucket） |
| Service | `service/BroadcastQueueService.java` | 新增（Stream + Sorted Set） |
| Service | `service/BroadcastChunkProcessor.java` | 新增（per-chunk 處理） |
| Service | `service/BroadcastWorkerManager.java` | 新增（N workers 啟動） |
| Service | `service/BroadcastRetryScheduler.java` | 新增（@Scheduled 重試） |
| Service | `service/BroadcastCounterService.java` | 新增（Redis 計數 + flush） |
| Service | `service/BroadcastService.java` | submit 改為 push to stream；移除舊 dispatch；testSend 從舊類搬過來 |
| Service | `service/BroadcastDispatchService.java` | **刪除** |
| Controller | `controller/BroadcastController.java` | 移除對 BroadcastDispatchService 的依賴 |

### 設定（application.yml 預設值已寫在程式碼中）

```yaml
broadcast:
  workers:
    count: 4                       # worker 數量
    block-ms: 5000                 # XREADGROUP 阻塞時間
  rate-limit:
    multicast:
      capacity: 60                 # token bucket 容量
      refill-per-second: 1         # 每秒補充速率
  retry:
    scan-interval-ms: 1000         # 重試掃描間隔
  counter:
    flush-interval-ms: 5000        # Redis 計數同步回 DB 間隔
```

---

## 三、架構流程

```
┌──────────────────┐
│ BroadcastService │ submit()：建立 chunks → 推入 Stream
│                  │
│  ┌──────────┐    │
│  │ enqueue  ├────┼───► XADD broadcast:chunks:queue {chunkId}
│  └──────────┘    │
└──────────────────┘
         ▲
         │
┌────────┴──────────────────────────────────────────────────────┐
│           Redis                                                │
│  ┌────────────────────┐   ┌──────────────────────┐             │
│  │ Stream:            │   │ Sorted Set:           │            │
│  │ broadcast:chunks:  │◄──┤ broadcast:retry:zset  │            │
│  │ queue              │   │ (score=next_retry_ms) │            │
│  └─────────┬──────────┘   └──────────▲────────────┘            │
│            │                          │                        │
│  ┌─────────┴────────┐                 │                        │
│  │ Consumer Group:  │                 │ scheduleRetry          │
│  │ broadcast-workers│                 │                        │
│  └─────────┬────────┘                 │                        │
│            │ XREADGROUP               │                        │
│            ▼                          │                        │
│  ┌────────────────────────────────────┴────┐                   │
│  │  BroadcastChunkProcessor                │                   │
│  │   1. rateLimiter.acquire()              │                   │
│  │   2. multicast(retryKey, request)       │                   │
│  │   3. counter.recordChunkResult()        │                   │
│  │   4. 失敗 → scheduleRetry / FAILED      │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                │
│  ┌──────────────────────┐  ┌─────────────────────────────┐    │
│  │ Counter Keys         │  │ Token Bucket Lua            │    │
│  │ broadcast:task:{id}: │  │ rate:multicast              │    │
│  │   sent/success/      │  │   {tokens, ts}              │    │
│  │   failed/total       │  │                             │    │
│  └──────────┬───────────┘  └─────────────────────────────┘    │
│             │ @Scheduled flush 5s                              │
│             ▼                                                  │
│  ┌──────────────────────┐                                      │
│  │ DB: broadcast_tasks  │  ← finalize 由「最後一個」worker     │
│  │  sent / success /    │     原子偵測（INCR 達 total）        │
│  │  failed / status     │                                      │
│  └──────────────────────┘                                      │
└────────────────────────────────────────────────────────────────┘

Worker 1 ─┐
Worker 2 ─┼─► 共同消費 Stream，由 Redis 自動分配訊息（partition-free）
Worker 3 ─┤
Worker 4 ─┘
```

---

## 四、關鍵設計決策

### 1. Redis Stream + Consumer Group（不用外部 MQ）
- 專案已用 Redis 做 Cache / Rate Limit，再加入 MQ 會增加維運面積。
- Stream 提供：at-least-once、PEL 追蹤未 ACK 訊息、Consumer Group 分散消費。
- **限制：** PEL 中崩潰 worker 留下的訊息 Phase 4 會用 `XCLAIM` 處理（dead-letter）。

### 2. Token Bucket 用 Lua 腳本（不用 Bucket4j）
- 手寫 Lua 在 Redis server-side 執行：原子性 + 一個 round-trip 完成 refill + consume。
- 不引入 Bucket4j 額外依賴，且更能展示對 Redis Lua 的掌握。
- **限速值：** 60 req/min（容量 60、每秒 refill 1），比 LINE 給的限制保守留 buffer。

### 3. 重試走 Sorted Set 而非直接 sleep
- 失敗的 chunk 不立即重排入 stream（會被立刻 retry），而是放進 `broadcast:retry:zset`，score = 應重試的 epoch millis。
- `BroadcastRetryScheduler` 每秒 `ZRANGEBYSCORE 0 now` 取出到期項目，重新 XADD 回 stream。
- **優點：** 重試不阻塞 worker 執行緒、可被取消（cancel 時 `ZREM`）。

### 4. Redis 計數避免 task hot row
- 每個 chunk 完成都更新 `broadcast_tasks.sent_count` 會造成同一列高頻寫入（hot row）。
- 改為 Redis `INCR broadcast:task:{id}:sent`，每 5s 由 `BroadcastCounterService.flushDirtyTasks()` 批次寫回 DB。
- DB 不再是即時數據來源，但 API 進度查詢仍從 DB 讀（5s 內的精度差可接受；若要即時可改讀 Redis）。

### 5. 原子終止判定（Lua）
- 多 worker 並行時不能用「if sent == total then finalize」這種 read-then-act，會競態。
- Counter Service 的 INCR Lua 腳本同步判定「本次 INCR 後是否達到 total」，僅該次 INCR 對應的 worker 收到 `isLast=1`。
- 此 worker 負責呼叫 `finalizeTask`，確定只執行一次。

### 6. Worker 不依賴 @Async
- 用 `broadcastWorkerExecutor` 直接 `executor.execute(runnable)` 啟動 worker loop。
- 比 `@Async` 控制力更強（可知道 worker 數量、可正確 graceful shutdown）。
- `running.set(false)` + `XREADGROUP BLOCK` 有 timeout，最差延遲 5s 後所有 worker 退出。

### 7. LINE Retry Key 對應 chunk
- `UUID.nameUUIDFromBytes("broadcast-{chunkId}-{attempts}")` 衍生。
- attempt 不變 → 同一個 retry key → LINE 視為同一次請求不重複扣配額。
- attempt+1 → 新 retry key → 視為新請求（這是我們重試的本意）。

---

## 五、驗證結果

- ✅ `mvn -o compile` 通過
- ✅ Service 單元測試（`*ServiceTest`）通過
- ⚠️ 整合測試需 `docker-compose up redis -d` 後執行（既有規範）

---

## 六、可調整的參數

| application.yml 屬性 | 預設 | 用途 |
|----------------------|------|------|
| `broadcast.workers.count` | 4 | Worker 數量；視 LINE 限制與 CPU 調整 |
| `broadcast.workers.block-ms` | 5000 | XREADGROUP 阻塞時間（毫秒） |
| `broadcast.rate-limit.multicast.capacity` | 60 | Token bucket 容量 |
| `broadcast.rate-limit.multicast.refill-per-second` | 1 | 每秒補充 token 數 |
| `broadcast.retry.scan-interval-ms` | 1000 | 重試掃描頻率（毫秒） |
| `broadcast.counter.flush-interval-ms` | 5000 | Redis 計數同步回 DB 頻率（毫秒） |

---

## 七、尚未完成（後續 Phase 處理）

| 項目 | 規劃階段 |
|------|---------|
| 崩潰 worker 的 PEL dead-letter handling（XCLAIM / XAUTOCLAIM） | Phase 4 |
| SSE 即時進度推送（取代 3 秒輪詢） | Phase 4 |
| 失敗清單頁、成效統計圖表 | Phase 4 |
| Flex Message 視覺化編輯器 | Phase 5 |
| Narrowcast API、A/B 測試、排程推播 | Phase 6 |

---

## 八、本階段技術亮點（面試展示）

1. **不用外部 MQ，用 Redis Stream 實作 producer/consumer + at-least-once** —— 證明對 Redis 進階特性的掌握。
2. **Lua 腳本實作 token bucket** —— 原子操作避免競態、跨實例共享，比客戶端限流更扎實。
3. **原子終止判定** —— 多 worker 中只有一個觸發 finalize，靠 Lua 內的 `if newSent >= total`。
4. **重試走 sorted set 排程，不阻塞 worker** —— 失敗 chunk 不浪費 worker time，且可被原子取消。
5. **Hot row 避免** —— task 表的計數欄位不再受高頻寫入衝擊，由 Redis 累計、批次 flush。
6. **Worker 生命週期可控** —— 不依賴 @Async，主動管理 worker pool 與 graceful shutdown。
7. **LINE 配額友善** —— retry key 設計讓重試不重複扣配額；rate limiter 比官方限制保守留 buffer。

---

## 九、下一階段預告

**Phase 4 — 進度與成效**
- SSE 即時進度推送（取代輪詢），Redis Pub/Sub 廣播事件
- 失敗清單頁面（顯示哪些 chunk 失敗、原因）
- 成效統計圖表（成功率、發送速率、時間分佈）
- PEL dead-letter 監控（崩潰 worker 留下的訊息處理）
