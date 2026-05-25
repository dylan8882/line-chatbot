# Phase 6 完成總結 — 加分項全集

> 完成日期：2026-05-26
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 上一階段：[Phase 5 — Flex 編輯器](./broadcast-phase5-summary.md)
>
> 範圍：排程推播、權限細分（Role-based）、Narrowcast 整合、A/B 測試
>
> Click tracking 移至 Phase 7。

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| 排程推播 | ✅ | `scheduledAt` + `BroadcastScheduler` @Scheduled 每 30s 觸發 |
| 權限細分（Role-based） | ✅ | 5 角色 + `@PreAuthorize` + `usePermissions` hook |
| `created_by` 從 SecurityContext 帶入 | ✅ | BroadcastTask、Tag、MessageTemplate |
| Narrowcast 整合 | ✅ | LINE Narrowcast API + 進度 poller |
| A/B 測試（多 variant） | ✅ | 2-4 variants、隨機切量、比較頁 |

---

## 二、新增與修改檔案

### 後端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Migration | `mysql/V7__broadcast_phase6.sql` | 新增 ab_test_id / variant_label / narrowcast_request_id |
| Migration | `postgres/V7__broadcast_phase6.sql` | 同上 |
| Entity | `BroadcastTask.java` | 加 3 個欄位 |
| DTO | `BroadcastTaskDTO.java` | 加 abTestId/variantLabel/narrowcastRequestId |
| DTO | `model/dto/AbTestCreateRequest.java` | 新增 |
| DTO | `model/dto/AbTestComparisonDTO.java` | 新增 |
| Security | `security/CurrentUserService.java` | 新增（從 SecurityContext 解出當前 User） |
| Service | `service/BroadcastScheduler.java` | 新增（排程 poller） |
| Service | `service/BroadcastNarrowcastPoller.java` | 新增（Narrowcast 進度追蹤） |
| Service | `service/BroadcastService.java` | submit() NARROWCAST 分支、createAbTest / getAbTestComparison、initial status SCHEDULED、createdBy |
| Service | `service/TagService.java` | createdBy 接 SecurityContext |
| Service | `service/MessageTemplateService.java` | createdBy 接 SecurityContext |
| Repository | `repository/BroadcastTaskRepository.java` | 新增 findDueScheduled / findByAbTestIdOrderByVariantLabel / findByTargetTypeAndStatus |
| Repository | `repository/LineUserRepository.java` | 新增 findIdsByLineUserIds（A/B 切分需要） |
| Controller | `controller/BroadcastController.java` | 加 `@PreAuthorize` + A/B 端點 |
| Controller | `controller/TagController.java` | 加 `@PreAuthorize` |
| Controller | `controller/MessageTemplateController.java` | 加 `@PreAuthorize` |
| Controller | `controller/LineUserController.java` | 加 `@PreAuthorize` |
| Controller | `controller/LineSettingsController.java` | 加 `@PreAuthorize("hasRole('ADMIN')")` |

### 前端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Hook | `hooks/usePermissions.ts` | 新增（role-based UI hiding） |
| Types | `types/index.ts` | NARROWCAST target、AbTest 相關型別、Task 加 abTestId 等 |
| API | `api/broadcasts.ts` | createAbTest / getAbTestComparison |
| 頁面 | `pages/AbTestCreate.tsx` | 新增（多 variant 建立頁） |
| 頁面 | `pages/AbTestComparison.tsx` | 新增（變體比較頁，含勝出標示） |
| 頁面 | `pages/BroadcastCreate.tsx` | 加 DatePicker、NARROWCAST 選項 |
| 頁面 | `pages/BroadcastList.tsx` | 「新增 A/B 測試」按鈕、用 usePermissions 隱藏建立按鈕 |
| App | `App.tsx` | 新增 `/broadcasts/ab-test/new`、`/broadcasts/ab-test/:abTestId` 路由 |

---

## 三、角色矩陣

| 操作 | VIEWER | CS_AGENT | MARKETER | MANAGER | ADMIN |
|------|--------|----------|----------|---------|-------|
| 查看所有資料 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 貼標籤給用戶 | ❌ | ✅ | ✅ | ✅ | ✅ |
| 管理訊息模板 | ❌ | ❌ | ✅ | ✅ | ✅ |
| 建立推播草稿、測試發送 | ❌ | ❌ | ✅ | ✅ | ✅ |
| 建立 A/B 測試 | ❌ | ❌ | ✅ | ✅ | ✅ |
| 送出 / 取消推播 | ❌ | ❌ | ❌ | ✅ | ✅ |
| 管理標籤（CRUD） | ❌ | ❌ | ❌ | ✅ | ✅ |
| LINE 串接設定 | ❌ | ❌ | ❌ | ❌ | ✅ |

**舊有資料相容性**：先前的 `ADMIN` 帳號（DataInitializer 種的 admin）落在最頂層，所有 `@PreAuthorize("hasAnyRole(... , 'ADMIN')")` 都會通過。新角色靠 DB `users.role` 欄位直接寫入。

---

## 四、排程推播流程

```
1. 建立任務時帶 scheduledAt（未來時間）→ status=SCHEDULED
                       │
                       ▼
2. BroadcastScheduler 每 30s 跑：
     findDueScheduled(now)
     for each task: submit(task.id)
                       │
                       ▼
3. submit 走正常派發路徑：
     - 一般任務 → 切 chunks → 推 Stream → workers
     - NARROWCAST → 直接呼叫 LINE Narrowcast API
```

如要取消排程：點任務「取消」按鈕（與一般任務同流程），會將狀態改為 CANCELLED。

---

## 五、Narrowcast 流程

```
submit (target=NARROWCAST)
        │
        ▼
messagingApiClient.narrowcast(retryKey, NarrowcastRequest)
  - recipient=null, filter=null → 推送給全體已加好友
  - 取得 X-Line-Request-Id 存到 task.narrowcastRequestId
  - status=RUNNING
        │
        ▼
BroadcastNarrowcastPoller @Scheduled 每 15s：
  - 對所有 RUNNING + targetType=NARROWCAST 的任務
  - 呼叫 getNarrowcastProgress(requestId)
  - 依 phase 更新狀態：
      waiting/sending → 維持 RUNNING + 廣播 PROGRESS 事件
      succeeded → COMPLETED + 廣播 COMPLETED 事件
      failed → FAILED + errorMessage
  - 更新 totalRecipients / success / failed 計數
```

**與 multicast 自管派發的差別：**

| | multicast (Phase 3) | narrowcast (Phase 6) |
|--|---|---|
| 收件人管理 | 後台自管 line_users + tags | LINE 平台自管 |
| 規模上限 | 自管 chunks 500/批 | 單次數十萬以上 |
| 結果追蹤 | per-chunk 成敗 | 僅彙總統計 |
| 速率控制 | 我們的 Token Bucket | LINE 平台自有 |
| 適用 | <100K + 需精準追蹤 | >100K + 廣播性質 |

---

## 六、A/B 測試流程

```
建立 A/B 測試：
  1. 後台填表（name + 2~4 variants + 目標 + 排程）
  2. 後端 BroadcastService.createAbTest:
     a. 計算 audience（依目標類型）
     b. Collections.shuffle 隨機打亂
     c. 依 trafficPercent 累計切點，分成 N 段
     d. 對每段建立獨立 BroadcastTask（target=USER_LIST）
        - 同 abTestId
        - 不同 variantLabel ("A", "B", ...)
        - 不同 message_content (來自 variant.templateId)
  3. 回傳 N 個 task DTO

各 variant 任務後續：
  - 跟一般任務無差，可分別 submit / cancel
  - 走 Phase 3 的 Stream + Worker 派發流程
  - 透過 abTestId 可在 /broadcasts/ab-test/{abTestId} 查看比較

比較邏輯：
  - 各 variant 成功率 = success / (success + failed)
  - UI 在最高成功率的 variant 標 👑 勝出
  - 數值現在以「送達率」為主；click rate 等指標需 Phase 7 click tracking
```

---

## 七、驗證結果

- ✅ `mvn -o compile` 通過
- ✅ `npx tsc --noEmit` 通過
- ⚠️ 端對端 narrowcast、A/B 測試需 Redis + LINE 真實連線測試

---

## 八、可調整的參數

| `application.yml` 屬性 | 預設 | 用途 |
|----------------------|------|------|
| `broadcast.scheduler.interval-ms` | 30000 | SCHEDULED 任務 poller 頻率 |
| `broadcast.narrowcast.poll-interval-ms` | 15000 | Narrowcast 進度 poller 頻率 |

---

## 九、本階段技術亮點（面試展示）

1. **多角色 RBAC 加在既有 JWT 架構上** —— Spring `@PreAuthorize` SpEL + Filter 已輸出 `ROLE_xxx` authority，沒額外資料表也能立刻分權。
2. **Narrowcast vs Multicast 二路設計** —— 同一個 BroadcastTask 模型，submit() 內依 targetType 分支；前者外包給 LINE，後者自管 chunks。
3. **A/B 測試切量採 USER_LIST trick** —— 不新增資料表，借既有 target_type=USER_LIST 路徑，後端 shuffle + 切片成 N 個獨立任務。
4. **排程推播零侵入** —— 利用既有 `scheduled_at` 欄位 + 一個 30s scheduled task，沒動主流程。
5. **`@Lazy` / `CurrentUserService` 解循環依賴** —— Counter ↔ Progress、Service ↔ CurrentUser 都用 Bean 注入解耦。
6. **配套 polling 機制** —— Narrowcast 進度（外部 API）也整合進既有 SSE 廣播管線，前端不分 source 統一接 progress 事件。

---

## 十、尚未完成（Phase 7 處理）

| 項目 | 規劃階段 |
|------|---------|
| Click tracking（button URL 帶 task/variant 參數、落地頁回拋） | Phase 7 |
| A/B 測試以 click rate / conversion 為主要指標 | Phase 7（依賴上者） |
| 拖拉式 Flex 編輯器 | 獨立任務 |
| 角色管理 UI（admin 改 user role） | 視需求 |
| Audience API 整合（narrowcast 帶 audienceGroupId） | 視需求 |

---

## 十一、累計 commit 樹（含 Phase 6）

```
{COMMIT_PLACEHOLDER}  feat: Phase 6 — 加分項全集（排程 + 權限 + Narrowcast + A/B）
dd6b514               feat: Phase 5 — Flex Message 預覽、預設模板庫、匯入/匯出
eefc99e               feat: Phase 4 — SSE 即時進度、成效統計、失敗清單、PEL dead-letter
b1d95c4               chore: 新增 repo 層級 .gitignore 並提交 frontend lockfile
92e3000               feat: Phase 3 — 高併發核心（Redis Stream + Worker Pool + Token Bucket）
ca6b49c               feat: Phase 2 — 模板與簡單推播主流程
337e041               feat: Phase 1 — LINE 用戶資料與標籤管理
```
