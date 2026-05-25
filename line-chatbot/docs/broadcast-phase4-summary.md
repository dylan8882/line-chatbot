# Phase 4 完成總結 — 進度與成效

> 完成日期：2026-05-26
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 上一階段：[Phase 3 — 高併發核心](./broadcast-phase3-summary.md)
>
> 範圍：SSE 即時進度推送、成效統計、失敗清單、PEL dead-letter 監控

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| SSE 即時進度推送 | ✅ | EventSource + Redis Pub/Sub pattern subscription |
| 多後端實例事件分發 | ✅ | A 實例 publish → 所有訂閱實例都收到 |
| 成效統計 API | ✅ | 成功率、平均嘗試次數、發送速率、錯誤分布 |
| 失敗清單 API | ✅ | FAILED + RETRYING 的 chunk 詳細資訊 |
| PEL dead-letter 監控 | ✅ | XPENDING 偵測 idle 過久訊息、XCLAIM 重新處理 |
| 前端 SSE 連線 | ✅ | 進度即時更新、SSE 中斷 badge 顯示 |
| 成效視覺化 | ✅ | StatisticsPanel 數值卡 + Recharts 錯誤分布長條圖 |
| 失敗清單頁 | ✅ | FailureTable 顯示錯誤碼、訊息、上次嘗試、下次重試時間 |

---

## 二、新增與修改檔案

### 後端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Config | `config/RedisConfig.java` | 新增 `RedisMessageListenerContainer` bean |
| DTO | `model/dto/BroadcastProgressEvent.java` | 新增（SSE 事件 payload） |
| DTO | `model/dto/BroadcastStatisticsDTO.java` | 新增 |
| DTO | `model/dto/BroadcastFailureDTO.java` | 新增 |
| Service | `service/BroadcastEventChannels.java` | 新增（Pub/Sub channel 命名常數） |
| Service | `service/BroadcastProgressService.java` | 新增（SseEmitter pool + MessageListener） |
| Service | `service/BroadcastStatisticsService.java` | 新增（統計與失敗查詢） |
| Service | `service/BroadcastDeadLetterScheduler.java` | 新增（PEL XCLAIM 處理） |
| Service | `service/BroadcastCounterService.java` | 改：recordChunkResult / finalizeTask 廣播事件 |
| Service | `service/BroadcastQueueService.java` | 改：新增 PEL 查詢與 XCLAIM 包裝 |
| Service | `service/BroadcastService.java` | 改：cancel() 廣播 CANCELLED 事件 |
| Controller | `controller/BroadcastController.java` | 改：新增 `/progress/stream`、`/statistics`、`/failures` 端點 |
| Repository | `repository/BroadcastChunkRepository.java` | 改：新增 `findByTaskIdAndStatusInOrderByChunkIndex` |
| Security | `security/JwtAuthenticationFilter.java` | 改：SSE 端點接受 `?token=` query param |

### 前端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Types | `types/index.ts` | 新增 `BroadcastProgressEvent` / `BroadcastStatistics` / `BroadcastFailure` |
| API | `api/broadcasts.ts` | 新增 `getBroadcastStatistics` / `getBroadcastFailures` / `openProgressStream` |
| 元件 | `components/Broadcast/StatisticsPanel.tsx` | 新增（Statistic 卡 + Recharts 錯誤長條圖） |
| 元件 | `components/Broadcast/FailureTable.tsx` | 新增 |
| 頁面 | `pages/BroadcastDetail.tsx` | 重寫：SSE 取代輪詢、整合 StatisticsPanel 與 FailureTable、SSE 連線狀態 Badge |

---

## 三、SSE 架構

```
┌─────────────┐ 1.GET /progress/stream (Accept: text/event-stream, ?token=...)
│  Browser    ├──────────────────────────────────────────────────┐
│ EventSource │                                                   │
└──────▲──────┘                                                   ▼
       │                                              ┌─────────────────────────┐
       │ 4. event: progress                           │ JwtAuthenticationFilter │
       │ data: {sent: 50, success: 48, ...}           │  ?token= 接受           │
       │                                              └────────────┬────────────┘
       │                                                           │
       │                                                           ▼
       │                                              ┌─────────────────────────┐
       │                                              │ BroadcastController     │
       │                                              │  .progressStream(id)    │
       │                                              └────────────┬────────────┘
       │                                                           │
       │                                                           ▼
       │       ┌──────────────────────────────────────────────────────────┐
       │       │ BroadcastProgressService                                  │
       │       │  emitters: Map<taskId, List<SseEmitter>>                  │
       │       │                                                            │
       │       │  subscribe(taskId): 加入 emitter, return                  │
       └───────┤  publish(event): convertAndSend → Redis Pub/Sub          │
               │  MessageListener: 收到 channel 訊息 → 找對應 emitters    │
               └──────────┬──────────────────────────────────┬─────────────┘
                          │                                  │ 3.收到 → emitter.send
                          │ 2.convertAndSend                 │
                          ▼                                  │
              ┌──────────────────────────┐                   │
              │ Redis                    │                   │
              │ broadcast:progress:{id}  │───────────────────┘
              │ (Pattern subscription)   │
              └────────▲─────────────────┘
                       │ PUBLISH
                       │
   ┌───────────────────┴─────────────────────────────────────────────┐
   │  Worker A (recordChunkResult)                                   │
   │  Worker B (finalizeTask)                                        │
   │  BroadcastService (cancel)                                      │
   │     ↓ progressService.publish(event)                            │
   └─────────────────────────────────────────────────────────────────┘
```

**多實例分發**：A 實例 PUBLISH 後，B 實例（前端連的那台）的 MessageListener 也會收到，
因為兩邊 Lettuce 連線都訂閱了同一個 Redis pattern。

---

## 四、Dead-letter（PEL）處理

```
worker crash → 訊息留在 PEL（pending entries list），永遠不被 ACK
                 │
                 ▼
@Scheduled 每 60s 跑 BroadcastDeadLetterScheduler.scanAndReclaim()
                 │
                 ▼
  1. XPENDING 取得整個 group 的 PEL summary
  2. XPENDING IDLE 取得 idle > 60s 的訊息列表
  3. 對每筆：
     a. XCLAIM (group, "dead-letter-handler", minIdle=60s, id)
     b. 解析 chunkId，呼叫 BroadcastChunkProcessor.process(chunkId)
     c. 成功處理 → XACK 從 PEL 移除
     d. 失敗 → 留在 PEL，下輪繼續重新認領
                 │
                 ▼
  ChunkProcessor 仍受 maxAttempts 保護，最終會標記為 FAILED 並 ACK
```

---

## 五、新增的 API 端點

| Method | Path | 用途 |
|--------|------|------|
| GET | `/api/broadcasts/{id}/progress/stream` | SSE 即時進度推送（`?token=`） |
| GET | `/api/broadcasts/{id}/statistics` | 成功率、發送速率、錯誤分布等 |
| GET | `/api/broadcasts/{id}/failures` | FAILED + RETRYING chunk 詳細列表 |

---

## 六、關鍵設計決策

### 1. SSE 而非 WebSocket
- 進度推送是**單向廣播**（server → browser），不需要 client → server 訊息。
- SSE 自動重連（瀏覽器 EventSource API 內建）、走 HTTP/HTTPS（穿透 proxy 不需要特殊處理）。
- 比 WebSocket 輕量、實作簡單。

### 2. Redis Pub/Sub Pattern Subscription
- 不用為每個 task 單獨 subscribe；用 pattern `broadcast:progress:*` 一次涵蓋。
- 後端收到訊息後從 channel 名稱抽出 taskId，分派給對應 emitters。
- 新任務不需要動態註冊 listener，零成本。

### 3. SSE 端點認證走 query string
- 瀏覽器 EventSource 不支援自訂 HTTP Header → 無法用 `Authorization: Bearer ...`。
- 折衷：`JwtAuthenticationFilter` 對 `Accept: text/event-stream` 的請求接受 `?token=` 參數。
- token 出現在 URL 有曝光於 server log 的風險，正式環境建議改為 cookie / reverse-proxy 注入 header。

### 4. 終態事件後自動斷線
- 前端 BroadcastDetail 偵測到 `task.status` 進入終態（COMPLETED / FAILED / CANCELLED）即關閉 SSE。
- 不浪費伺服器 emitter 資源；下次任務變動時重新開啟。

### 5. 成效統計即時計算（不存中間表）
- `BroadcastStatisticsService` 直接讀 `broadcast_chunks` 表計算所有指標。
- 規模小時最簡單；大量歷史任務時可改為定期 materialize 到摘要表。

### 6. Dead-letter 處理不直接丟棄
- XCLAIM 把訊息所有權移到 `dead-letter-handler` consumer，再呼叫 ChunkProcessor 重新處理。
- ChunkProcessor 的 `maxAttempts` 保護仍有效，最終達上限會 mark FAILED 並 ACK。
- 失敗 chunk 永遠不會無聲消失。

### 7. CounterService 對 ProgressService 用 `@Lazy`
- 兩者間若日後加更多互動可能形成循環依賴；先以 `@Lazy` 注入保險。
- 不影響執行效能，只在實際使用時才解析 bean。

---

## 七、驗證結果

- ✅ `mvn -o compile` 通過
- ✅ `npx tsc --noEmit` 通過
- ⚠️ 端對端 SSE / dead-letter 測試需 Redis 在跑（依專案既定規範）

---

## 八、可調整的參數

| `application.yml` 屬性 | 預設 | 用途 |
|----------------------|------|------|
| `broadcast.deadletter.idle-ms` | 60000 | PEL 訊息 idle 超過此值視為 dead-letter |
| `broadcast.deadletter.scan-interval-ms` | 60000 | dead-letter 掃描頻率 |
| `broadcast.deadletter.batch-size` | 50 | 單次掃描最多處理筆數 |

---

## 九、本階段技術亮點（面試展示）

1. **SSE + Redis Pub/Sub** —— 不依賴 sticky session，多實例水平擴展時前端 SSE 連到任一台都能收到事件。
2. **Pattern Subscription 取代逐 task 訂閱** —— 用一個 listener 處理所有 task 廣播，零動態管理成本。
3. **EventSource 認證限制的折衷方案** —— 用 `Accept` header 區分後接受 query token，標明這是 trade-off。
4. **Dead-letter handler 不丟棄訊息** —— XCLAIM 重新認領而非無聲消失；配合 maxAttempts 形成完整可觀察的失敗閉環。
5. **終態事件後 SSE 自動斷線** —— 不浪費 server 資源；前端透過 status 變化驅動連線生命週期。
6. **統計即時計算** —— 不需要額外 materialized view，DB query 就完成；後續可依規模升級。
7. **跨 service 廣播解耦** —— Counter/Service/Worker 都透過 `progressService.publish()` 一個介面送事件，與 SSE 細節隔離。

---

## 十、尚未完成（後續 Phase 處理）

| 項目 | 規劃階段 |
|------|---------|
| Flex Message 視覺化編輯器 | Phase 5 |
| 模板庫（問候 / 商品 / 優惠券 / 活動 4 種起跳） | Phase 5 |
| Narrowcast API、A/B 測試、排程推播 | Phase 6 |
| SSE 認證強化（cookie token） | Phase 6 / 加強項 |
| Statistics 寫入 materialized 表（大量歷史任務時） | Phase 6 / 加強項 |

---

## 十一、下一階段預告

**Phase 5 — Flex 編輯器**
- Flex Message JSON 即時預覽（LINE 風格泡泡）
- 拖拉式編輯器（Bubble / Carousel / Box / Text / Image / Button）
- 預設模板庫（4 種起跳）
- 模板匯入 / 匯出
