# Phase 2 完成總結 — 模板與簡單推播

> 完成日期：2026-05-25
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 上一階段：[Phase 1 — 推播基礎建設](./broadcast-phase1-summary.md)
>
> 範圍：訊息模板 CRUD、推播任務生命週期、multicast 500/批同步派發

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| MessageTemplate 資料表與 CRUD | ✅ | JSON 內容驗證（非空陣列、≤5 則訊息） |
| BroadcastTask / BroadcastChunk 資料表 | ✅ | 含完整狀態機欄位（status、scheduled_at、計數） |
| 推播任務建立與狀態管理 | ✅ | DRAFT / RUNNING / COMPLETED / FAILED / CANCELLED |
| multicast 500 人/批自管分批 | ✅ | `BroadcastConfig.CHUNK_SIZE = 500` |
| 同步派發主流程（無 Queue） | ✅ | `@Async("broadcastWorkerExecutor")` 單一執行緒迴圈 |
| 收件人預估 API | ✅ | 不建立任何資料 |
| 測試發送 API | ✅ | 用 pushMessage 對單一 userId，不影響任務統計 |
| 任務取消 | ✅ | 派發迴圈會在每個 chunk 之間檢查取消狀態 |
| 冪等鍵 | ✅ | `idempotency_key` UNIQUE，相同鍵直接回傳既有任務 |
| 多標籤匹配（ANY / ALL） | ✅ | ANY 走 DB query；ALL 在記憶體內 retainAll |
| LINE Retry Key | ✅ | 用 `chunkId + attempts` 衍生穩定 UUID |

---

## 二、新增與修改檔案

### 後端

| 類別 | 檔案 |
|------|------|
| Migration | `backend/src/main/resources/db/migration/mysql/V5__broadcast_phase2.sql` |
| Migration | `backend/src/main/resources/db/migration/postgres/V5__broadcast_phase2.sql` |
| Entity | `backend/src/main/java/com/linechatbot/model/entity/MessageTemplate.java` |
| Entity | `backend/src/main/java/com/linechatbot/model/entity/BroadcastTask.java` |
| Entity | `backend/src/main/java/com/linechatbot/model/entity/BroadcastChunk.java` |
| Repository | `backend/src/main/java/com/linechatbot/repository/MessageTemplateRepository.java` |
| Repository | `backend/src/main/java/com/linechatbot/repository/BroadcastTaskRepository.java` |
| Repository | `backend/src/main/java/com/linechatbot/repository/BroadcastChunkRepository.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/MessageTemplateDTO.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/BroadcastCreateRequest.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/BroadcastTaskDTO.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/BroadcastEstimateResponse.java` |
| Config | `backend/src/main/java/com/linechatbot/config/BroadcastConfig.java`（`broadcastWorkerExecutor`） |
| Service | `backend/src/main/java/com/linechatbot/service/MessageTemplateService.java` |
| Service | `backend/src/main/java/com/linechatbot/service/BroadcastService.java` |
| Service | `backend/src/main/java/com/linechatbot/service/BroadcastDispatchService.java` |
| Controller | `backend/src/main/java/com/linechatbot/controller/MessageTemplateController.java` |
| Controller | `backend/src/main/java/com/linechatbot/controller/BroadcastController.java` |
| 修改 | `backend/src/main/java/com/linechatbot/repository/LineUserRepository.java`（新增 `findAllFollowedLineUserIds`、`findLineUserIdsByIds`） |

### 前端

| 類別 | 檔案 |
|------|------|
| Types | `frontend/src/types/index.ts`（新增 MessageTemplate / BroadcastTask / BroadcastCreateRequest 等型別） |
| API | `frontend/src/api/messageTemplates.ts` |
| API | `frontend/src/api/broadcasts.ts` |
| 頁面 | `frontend/src/pages/MessageTemplates.tsx` |
| 頁面 | `frontend/src/pages/BroadcastList.tsx` |
| 頁面 | `frontend/src/pages/BroadcastCreate.tsx` |
| 頁面 | `frontend/src/pages/BroadcastDetail.tsx` |
| 修改 | `frontend/src/App.tsx`（新增 `/templates`、`/broadcasts`、`/broadcasts/new`、`/broadcasts/:id` 路由） |
| 修改 | `frontend/src/components/Layout/Sidebar.tsx`（新增訊息模板與推播管理選單） |

---

## 三、資料表結構

```
message_templates
├── id BIGINT PK
├── name VARCHAR(100)
├── message_type VARCHAR(20)              -- TEXT / FLEX / IMAGE / TEMPLATE
├── content JSON                          -- LINE messages 物件陣列
├── thumbnail VARCHAR(500)
├── created_by BIGINT FK users
└── created_at, updated_at

broadcast_tasks
├── id BIGINT PK
├── name VARCHAR(200)
├── message_content JSON                  -- 送出當下的訊息 snapshot
├── target_type VARCHAR(20)               -- ALL / TAGS / USER_LIST
├── target_filter JSON                    -- {tagIds, tagMatch, userIds}
├── status VARCHAR(20)                    -- 完整狀態機（見下方）
├── total_recipients, sent_count, success_count, failed_count
├── scheduled_at, started_at, finished_at
├── idempotency_key VARCHAR(64) UNIQUE
├── error_message TEXT
├── created_by BIGINT FK users
└── created_at, updated_at
    INDEX(status, scheduled_at)
    INDEX(created_at DESC)

broadcast_chunks
├── id BIGINT PK
├── task_id BIGINT FK broadcast_tasks   ON DELETE CASCADE
├── chunk_index INT
├── recipient_ids JSON                    -- 該片的 LINE userId 陣列
├── status VARCHAR(20)                    -- PENDING / SENDING / SUCCESS / FAILED / RETRYING
├── attempts INT
├── line_request_id VARCHAR(100)          -- LINE API 回傳的 X-Line-Request-Id
├── error_code, error_message
├── sent_at
└── INDEX(task_id, status), INDEX(status, attempts)
```

任務狀態機：

```
DRAFT ──submit──► RUNNING ──完成──► COMPLETED
  │                  │
  │                  ├──全失敗──► FAILED
  │                  │
  │                  └──cancel──► CANCELLED
  │
  └─（idempotency 命中）─► 直接回傳既有任務
```

---

## 四、API 端點

| Method | Path | 用途 |
|--------|------|------|
| GET | `/api/message-templates` | 列出所有模板 |
| GET | `/api/message-templates/{id}` | 查詢單一模板 |
| POST | `/api/message-templates` | 新增模板（驗證 JSON 格式） |
| PUT | `/api/message-templates/{id}` | 修改模板 |
| DELETE | `/api/message-templates/{id}` | 刪除模板 |
| GET | `/api/broadcasts` | 分頁查詢任務（`status` 篩選） |
| GET | `/api/broadcasts/{id}` | 任務詳情（含 chunk 摘要） |
| POST | `/api/broadcasts` | 建立 DRAFT 任務（支援 `idempotencyKey`） |
| POST | `/api/broadcasts/estimate` | 預估收件人數與分片數 |
| POST | `/api/broadcasts/{id}/test` | 測試發送（pushMessage 給單一 userId） |
| POST | `/api/broadcasts/{id}/submit` | 提交執行（建立 chunks → 啟動 async 派發） |
| POST | `/api/broadcasts/{id}/cancel` | 取消任務 |
| GET | `/api/broadcasts/{id}/progress` | 一次性查詢進度（Phase 4 加上 SSE 即時版） |

---

## 五、關鍵設計決策

### 1. JSON 欄位用 `@JdbcTypeCode(SqlTypes.JSON)` + `String`
- Hibernate 6 在 MySQL 用 `JSON`、PostgreSQL 用 `JSONB`，由 SDK 自動處理 dialect 差異。
- 用 `String` 而非 `JsonNode`：保持原始 JSON 不破壞，發送時直接交給 Jackson 反序列化為 LINE SDK 的 `List<Message>`。

### 2. 訊息內容 snapshot 寫入任務
- 任務建立時把模板 / 自訂內容固化到 `broadcast_tasks.message_content`，之後改模板不會影響已發送任務。
- **目的：** 模板更新不可回溯影響歷史推播；稽核時看到的就是當時實際送出的內容。

### 3. `BroadcastDispatchService` 不依賴 dirty-checking
- 所有 DB 寫入都用 explicit `save()`，不依賴 `@Transactional` 自動 flush。
- **原因：** `@Async` 方法在新執行緒執行，且同類別內 self-call 不會經 Spring proxy。若用 `@Transactional` 在 sendChunk 之類的 private/同類方法上，AOP 不會生效。explicit save 避免這個陷阱。

### 4. Retry Key 用 `chunkId + attempts` 衍生
- `UUID.nameUUIDFromBytes("broadcast-{chunkId}-{attempts}")` 產生穩定 UUID。
- **目的：** LINE 平台用 `X-Line-Retry-Key` 識別重試，相同 key 不會重複扣訊息配額；attempts 變化代表新一輪嘗試。

### 5. 多標籤 ALL 匹配在記憶體計算
- DB query 對「同時擁有標籤 A、B、C」較難寫得高效（需 GROUP BY HAVING）。
- 目前實作：每個 tag 拿一次用戶集合，`Set.retainAll` 求交集，並在 early exit 條件下提前結束。
- **限制：** 大型用戶量 + 多標籤 ALL 時記憶體吃緊；Phase 3 / 4 改寫成 SQL `GROUP BY HAVING COUNT(*) = N`。

### 6. 同步派發版本（無 Queue）
- 整個任務一個 `@Async` 方法，內部 for-loop 依序處理 chunks。
- 優點：簡單、好除錯、流程清楚。
- 缺點：單機單執行緒、無速率控制、無分散式 worker；Phase 3 會用 Redis Stream + Worker Pool 取代。

### 7. 任務取消是「軟取消」
- 每個 chunk 開始前檢查 task.status，若為 CANCELLED 就中止派發。
- 正在 SENDING 的 chunk 不會中斷（API 已發出），但結果寫回時不再改變 task 狀態。

---

## 六、驗證結果

- ✅ 後端 `mvn -o compile` 通過
- ✅ 前端 `npx tsc --noEmit` 通過

---

## 七、尚未完成（後續 Phase 處理）

| 項目 | 規劃階段 |
|------|---------|
| Redis Stream Queue + Worker Pool（多實例） | Phase 3 |
| Token Bucket 限速（Bucket4j） | Phase 3 |
| 失敗 chunk 自動重試（指數退避） | Phase 3 |
| SSE 即時進度推送（取代 3 秒輪詢） | Phase 4 |
| 失敗清單頁、成效統計圖表 | Phase 4 |
| Flex Message 視覺化編輯器 | Phase 5 |
| Narrowcast API、A/B 測試、排程推播 | Phase 6 |

---

## 八、可手動驗證的流程

1. 在「訊息模板」頁建立一個 TEXT 模板，內容例如：
   ```json
   [{"type": "text", "text": "哈囉，這是測試推播"}]
   ```
2. 在「LINE 用戶」頁確認有至少 1 位 FOLLOWED 用戶（透過 LINE Bot 加好友觸發 webhook）。
3. 在「推播管理」→「新增推播」：
   - 選擇剛建立的模板
   - 目標選「全部已加好友」
   - 點「預估」確認人數
   - 點「測試發送」可先用自己的 LINE userId 預覽
   - 點「直接送出」執行
4. 跳到任務詳情頁可看到進度條與 chunk 列表（每 3 秒輪詢一次）

---

## 九、下一階段預告

**Phase 3 — 高併發核心**
- 引入 Redis Stream 作為 chunk queue
- 多個 Worker 並行消費（不再單一執行緒）
- Token Bucket（Bucket4j + Redis）共享限速
- 失敗 chunk 指數退避重試
- chunk 狀態存 Redis，避免 task hot row
