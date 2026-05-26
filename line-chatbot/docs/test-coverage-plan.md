# 測試覆蓋規劃

> 撰寫日期：2026-05-26
>
> 對應於 Phase 1–7 推播功能完成後的測試補強規劃。記錄三種範圍選項與最終選擇。

---

## 一、現況

### 既有測試
- `QAServiceTest` — Phase 0 QA 比對邏輯（單元）
- `JwtBlacklistIntegrationTest` — JWT 黑名單 Redis 整合
- `QACacheIntegrationTest` — QA Cache evict 整合
- `RateLimitIntegrationTest` — Rate Limit Redis 整合

### 推播功能 (Phase 1–7) 累計新增類別（**全部無測試**）

| 類型 | 數量 | 代表類別 |
|------|------|---------|
| Entity | 8 | LineUser、Tag、BroadcastTask、BroadcastChunk、MessageTemplate、ClickLink、ClickEvent 等 |
| Repository | 9 | LineUserRepository、TagRepository、BroadcastTaskRepository、ClickLinkRepository 等 |
| Service | 14 | BroadcastService、BroadcastQueueService、BroadcastChunkProcessor、BroadcastCounterService、BroadcastProgressService、BroadcastStatisticsService、BroadcastDeadLetterScheduler、BroadcastScheduler、BroadcastNarrowcastPoller、ClickLinkRewriter、ClickTrackingService、LineUserService、TagService、MessageTemplateService、LineApiRateLimiter |
| Controller | 6 | BroadcastController、TagController、LineUserController、MessageTemplateController、ClickTrackingController、LineSettingsController（部分既有） |
| **共計** | **37+ 類別** | |

### 測試基礎建設
- JUnit 5 + Mockito（pom.xml 已配） ✅
- Spring Boot Test、Security Test ✅
- H2 in-memory DB（測試用） ✅
- `application-test.yml` ✅
- CLAUDE.md 已記錄單元 / 整合測試規範 ✅

→ 工具齊全，**只缺寫測試**。

---

## 二、三種範圍選項

### A. 最小可信（8–12 hr）

**目標：** 各 service 的 happy path + 1-2 個重要 edge case；commit 看起來「有測試」這件事成立。

**對象（依優先順序）：**
1. `BroadcastServiceTest` — create / submit / cancel / computeRecipients
2. `ClickLinkRewriterTest` — JSON 改寫（邊角案例多）
3. `BroadcastCounterServiceTest` — atomic isLast 邏輯
4. `BroadcastStatisticsServiceTest` — 聚合計算
5. `LineUserServiceTest` — follow / unfollow / tag
6. `TagServiceTest` — CRUD + 重複名稱

**不做：**
- 整合測試（需要 Redis）
- Controller 測試（與 service 重複）
- Scheduler / Worker 測試（涉及 thread / async，價值低）
- 前端測試

**產出：** 約 6 個 test class、共 50–70 個 test method、line coverage 約 40–50%。

### B. 合理展示（20–30 hr）

**目標：** 所有 public service 方法都測；1-2 個 E2E 整合測試 per Phase。

**新增於 A 之上：**

| Phase | 額外要測 | 預估 hr |
|-------|---------|---------|
| 1 | TagController MockMvc、@PreAuthorize 行為 | 1 |
| 2 | MessageTemplateService、BroadcastChunkRepository 查詢 | 1–2 |
| 3 | LineApiRateLimiter（Redis Lua，整合）、BroadcastChunkProcessor 完整路徑（成功/失敗/重試/上限）、BroadcastWorkerManager 啟動 | 4–5 |
| 4 | BroadcastProgressService 派發、Dead-letter XCLAIM 流程、Failure 清單 | 3–4 |
| 5 | 前端：FlexPreview snapshot、presets JSON validation | 1–2 |
| 6 | BroadcastScheduler 觸發、A/B 切分隨機性、Narrowcast phase 對應、@PreAuthorize MockMvc | 3–4 |
| 7 | ClickTrackingService 非同步寫入、Controller redirect + IP 抽取、CTR 計算 | 2 |
| **追加** | | **約 15–20 hr** |

**產出：** 約 15 個 test class、line coverage 約 65–75%。

### C. 完整覆蓋（40–60 hr）

**新增於 B 之上：**
- 每個 Repository 的 custom query 都有測試
- 所有 Controller 透過 MockMvc 測 200/400/401/403 路徑
- Redis 真實連線的整合測試（每個 Redis-dependent service）
- LINE Webhook 完整 E2E（mock LINE 平台請求）
- 前端 React Testing Library 測試 + Vitest

**產出：** line coverage 約 80%+、CI 跑全測試 5–10 min、commit 紀錄會冗長。

---

## 三、難測的部分（不論範圍）

| 困難點 | 對策 |
|--------|------|
| LINE SDK 呼叫（multicast / narrowcast / pushMessage） | Mockito mock `MessagingApiClient`；回傳假 `Result` |
| Redis Stream Consumer Group | 整合測試走真實 Redis（`docker-compose up redis -d`） |
| Redis Lua token bucket | 整合測試走 Redis；單元測試只能驗 KEY 與參數 |
| `@Async` 方法 | 單元測試直接呼叫方法，不走 proxy（測 logic）；整合測試另外驗執行緒 |
| `@Scheduled` poller | 單元測試直接呼叫方法；整合測試可暫時關閉 scheduler |
| SSE / Pub/Sub | 整合測試用 `RedisMessageListenerContainer` 真實送訊；或單元只驗 `publish()` 被呼叫 |
| `@PreAuthorize` 角色 | `@WithMockUser(roles = "...")` + MockMvc |
| JSON 改寫遞迴 | 純函式好測；準備多種 messages JSON fixture |
| `BroadcastCounterService.recordChunkResult` 內 Lua | Mock `redisTemplate.execute()` 回 0 或 1 模擬 isLast |

---

## 四、決策

**先做 A（最小可信）**，理由：
1. 投資報酬率最高 — 涵蓋核心業務邏輯
2. 不依賴 Redis / DB 起來，CI 容易跑
3. 後續整合測試可以等實際 demo / deploy 前再補

**A 的執行順序與 commit 規劃：**

```
test-1: 後端核心 service 單元測試（BroadcastService / ClickLinkRewriter / ...）
        → 預計一個 commit
```

整合測試（B、C 範圍）的 TODO 留在 `docs/test-coverage-plan.md`（本文件），未來要補時再回來。

---

## 五、最小可信版本的具體 test class 計畫

| Test Class | 涵蓋方法 | 大致 test 數 |
|-----------|---------|------------|
| `BroadcastServiceTest` | create（含 idempotency、scheduledAt → SCHEDULED）、submit（DRAFT/QUEUED/SCHEDULED 允許、其他拒絕、NARROWCAST 分支、empty recipients）、cancel、computeRecipients（ALL / TAGS ANY / TAGS ALL / USER_LIST）、createAbTest（traffic sum 驗證、N variants 建立） | 12–15 |
| `ClickLinkRewriterTest` | rewriteForTask 無 serverBaseUrl → 原樣、Flex 多 button → 全改寫、line.me 不改寫、空陣列、巢狀 box | 6–8 |
| `BroadcastCounterServiceTest` | initTask、recordChunkResult（Mock Lua 回 0 / 1）、finalizeTask（COMPLETED / FAILED / 部分失敗）、clearTask、publishProgress | 8–10 |
| `BroadcastStatisticsServiceTest` | getStatistics（聚合各狀態 chunk）、getFailures（過濾）、getClickStatistics（CTR 計算）、errorBreakdown 分組 | 8–10 |
| `LineUserServiceTest` | onFollow 新用戶 / 既有用戶、onUnfollow、touchOnMessage、assignTags 覆寫式、bulkTag ADD/REMOVE | 8–10 |
| `TagServiceTest` | create 重複名 throw、update 名稱衝突 throw、delete 不存在 throw、正常 CRUD | 6–8 |
| **合計** | | **48–61 tests** |

---

## 六、未來再補的 TODO

- [ ] Redis 整合測試：LineApiRateLimiter、BroadcastQueueService、BroadcastCounterService（recordChunkResult 與真實 Lua）
- [ ] BroadcastChunkProcessor 完整路徑測試（mock LINE SDK + Redis）
- [ ] BroadcastWorkerManager 啟停測試（XREADGROUP / XACK）
- [ ] Dead-letter XCLAIM 整合測試
- [ ] SSE 端到端測試（MockMvc + WebTestClient）
- [ ] Controller MockMvc 全套（含 @PreAuthorize 各 role 驗證）
- [ ] Narrowcast 進度 poll 整合
- [ ] LINE Webhook E2E
- [ ] 前端 React Testing Library：FlexPreview、ClickStatsPanel、AbTestComparison
- [ ] LINE SDK fault injection（429 rate limited / 5xx 重試流程）
