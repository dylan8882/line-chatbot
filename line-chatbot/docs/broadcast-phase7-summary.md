# Phase 7 完成總結 — Click Tracking

> 完成日期：2026-05-26
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 上一階段：[Phase 6 — 加分項全集](./broadcast-phase6-summary.md)
>
> 範圍：訊息內 button URL 點擊追蹤、CTR 統計、A/B 測試改以點擊率為主要指標

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| 訊息內 URL 自動改寫為 tracking link | ✅ | 遞迴掃 messages JSON，所有 `action.uri` 改寫 |
| 點擊事件記錄 | ✅ | click_events 表，IP / UA / Referer |
| 重定向不影響使用者體驗 | ✅ | 寫事件非同步、立即 302 重導 |
| 點擊統計 API | ✅ | 任務 CTR、不重複 IP、per-link 點擊數 |
| A/B 測試以 CTR 比較 | ✅ | 有點擊資料時改以 clickRate 決定勝出 |
| 前端 ClickStatsPanel | ✅ | BroadcastDetail 顯示 CTR 與各 link 點擊數 |

---

## 二、新增與修改檔案

### 後端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Migration | `mysql/V8__broadcast_phase7.sql` | 新增 click_links / click_events |
| Migration | `postgres/V8__broadcast_phase7.sql` | 同上 |
| Entity | `model/entity/ClickLink.java` | 新增 |
| Entity | `model/entity/ClickEvent.java` | 新增 |
| Repository | `repository/ClickLinkRepository.java` | 新增（含 incrementClickCount） |
| Repository | `repository/ClickEventRepository.java` | 新增 |
| DTO | `model/dto/ClickStatisticsDTO.java` | 新增 |
| DTO | `model/dto/AbTestComparisonDTO.java` | VariantStat 加 totalClicks / clickRate |
| Service | `service/ClickLinkRewriter.java` | 新增（URL 改寫器） |
| Service | `service/ClickTrackingService.java` | 新增（resolveAndRecord + @Async 事件寫入） |
| Service | `service/BroadcastService.java` | submit 前呼叫 rewriter；A/B 比較含 click 統計 |
| Service | `service/BroadcastStatisticsService.java` | 新增 getClickStatistics() |
| Controller | `controller/ClickTrackingController.java` | 新增 GET /c/{token} 公開端點 |
| Controller | `controller/BroadcastController.java` | 新增 GET /{id}/clicks 端點 |
| Security | `config/SecurityConfig.java` | 放行 /c/** |

### 前端

| 類別 | 檔案 | 變更 |
|------|------|------|
| Types | `types/index.ts` | 新增 ClickStatistics / ClickLinkStat、AbTestVariantStat 加 totalClicks / clickRate |
| API | `api/broadcasts.ts` | 新增 getBroadcastClicks |
| 元件 | `components/Broadcast/ClickStatsPanel.tsx` | 新增（CTR + per-link 點擊表） |
| 頁面 | `pages/BroadcastDetail.tsx` | 整合 ClickStatsPanel |
| 頁面 | `pages/AbTestComparison.tsx` | 加 點擊 / CTR 欄位，勝出邏輯優先採 CTR |

---

## 三、Click Tracking 流程

```
1. 後台建立任務 → submit
            │
            ▼
2. BroadcastService.submit：
   呼叫 ClickLinkRewriter.rewriteForTask(taskId, messageContent)
            │
            ▼
3. Rewriter 遞迴掃描 JSON：
   找所有 {type:"uri", uri:"https://..."} 節點
   對每一個：
     a. 產生短 token (~12 chars url-safe base64)
     b. INSERT click_links(task_id, link_index, target_url, token)
     c. 把 uri 改寫為 {serverBaseUrl}/c/{token}
            │
            ▼
4. 把改寫後的 messages JSON 存回 task.message_content
            │
            ▼
5. 走原本 multicast/narrowcast 流程送出

============= 使用者點擊 =============

6. 使用者點 button → 瀏覽器 GET {serverBaseUrl}/c/{token}
            │
            ▼
7. ClickTrackingController（公開、無需 JWT）：
   - 抓 IP（先 X-Forwarded-For，後 RemoteAddr）
   - 抓 User-Agent / Referer
   - 委派 ClickTrackingService.resolveAndRecord
            │
            ▼
8. ClickTrackingService：
   - SELECT click_links WHERE token=?
   - 非同步：INSERT click_events + UPDATE click_count + 1
   - 回傳 target_url
            │
            ▼
9. Controller 回 302 Location: {original_url}
   使用者瀏覽器轉向到原始網站

============ 統計查詢 ==============

10. GET /api/broadcasts/{id}/clicks → ClickStatisticsDTO
    - totalClicks（事件總數）
    - uniqueIps（distinct）
    - deliveredRecipients (task.success_count)
    - ctr = totalClicks / deliveredRecipients
    - links[] 各 link 點擊數
```

---

## 四、A/B 測試以 CTR 比較

**Before（Phase 6 結束時）**：勝出依「成功送達率」
- 但 LINE multicast 通常 99%+ 成功率，無區隔意義

**After（Phase 7）**：
- AbTestComparisonDTO.VariantStat 多了 `totalClicks` + `clickRate`
- BroadcastService.getAbTestComparison 為每個 variant 查 `clickEventRepository.countByTaskId`
- 前端 AbTestComparison：`hasClicks` 判斷至少一個 variant 有點擊
  - 是 → 勝出依 clickRate
  - 否 → 退回 successRate（兼容舊任務）
- 「勝出變體」面板顯示對應指標

---

## 五、設計決策

### 1. 不以 user 為單位追蹤點擊
**現實限制：** LINE multicast 一次 API 送 500 人共用同一份 messages JSON，無法在 URL 內帶不同的 `recipient_id` 參數。
**取捨：** Phase 7 只追蹤「任務級 / variant 級」點擊，能滿足 CTR、A/B 比較需求；個別 user 追蹤需要改走 pushMessage（每人一個 API call）→ 大幅增加 LINE 配額消耗。

### 2. URL 改寫時機在 submit，不在 create
- create 時還在 DRAFT 狀態、可能會編輯訊息；改了又改會產生孤兒 click_links
- submit 是 lock 訊息內容的時點（task.message_content snapshot）
- 副作用：取消任務後 click_links 仍存在但無流量；ON DELETE CASCADE 處理 task 被刪除時的清理

### 3. 短 token 用 9 bytes (~12 字元) base64-urlsafe
- 衝突機率：256^9 ≈ 4.7 × 10^21，遠超任何 side project 規模
- 比 UUID (36 字元) 短，URL 更乾淨
- DB UNIQUE constraint 兜底；極低機率衝突時 INSERT 會失敗、由 Spring 拋例外（生產環境可加 retry，目前略）

### 4. 寫事件非同步
- 重導體驗 = 使用者第一印象，**不能等 DB 寫入**
- `@Async("broadcastWorkerExecutor")` 直接送回背景執行，重導毫秒級回應
- click_count 用 `@Modifying` 原子 UPDATE，避免 race

### 5. LINE 自家連結不改寫
- `line.me` / `https://line.me/` 改寫會破壞 deeplink 行為（LINE App 內回呼）
- Rewriter 內白名單跳過

### 6. SecurityConfig 放行 `/c/**`
- 此端點本質就是公開的（給 LINE App 內瀏覽器或外部瀏覽器點擊）
- 不需 JWT；但仍依靠 token 不可預測性防止枚舉

---

## 六、驗證結果

- ✅ `mvn -o compile` 通過
- ✅ `npx tsc --noEmit` 通過
- 手動驗證項目（需啟動服務 + 設定 `serverBaseUrl`）：
  - 建模板含 button URL → 推播 → 訊息收到的 button 點下後正確重導
  - GET /api/broadcasts/{id}/clicks 回傳累計 CTR
  - A/B 測試在累積點擊資料後勝出指標切到 CTR

---

## 七、新增的 API 端點

| Method | Path | 公開？ | 用途 |
|--------|------|--------|------|
| GET | `/c/{token}` | ✅ 公開 | 點擊追蹤端點，記錄事件並 302 重導 |
| GET | `/api/broadcasts/{id}/clicks` | JWT | 任務的點擊統計（CTR + per-link） |

---

## 八、本階段技術亮點（面試展示）

1. **JSON 遞迴改寫保留結構** —— Jackson `JsonNode` mutate-in-place，所有 nested action 都能命中而不破壞 Flex 結構。
2. **重定向體驗優先** —— 寫事件 `@Async` + click_count 原子 UPDATE，redirect 不被 DB I/O 拖慢。
3. **與既有任務流程零侵入** —— 鉤子點放在 submit 一個地方，A/B / Narrowcast / 排程都自動受益。
4. **CTR 為主、送達率為輔** —— A/B 比較依資料可用性 graceful degrade，新舊任務都能正確顯示。
5. **隱私 / 安全考量** —— LINE 自家連結不破壞；click_link token 不可預測防止枚舉；IP 從 X-Forwarded-For 抽取，準備好 reverse proxy 部署。
6. **資料量規劃** —— click_events 高頻插入用 (link_id, created_at) 索引利於時間區間查詢；click_links.click_count 反正規化避免 COUNT(*) 全掃。

---

## 九、未做（後續方向）

| 項目 | 備註 |
|------|------|
| Per-user 點擊（pushMessage + 個別 retry key） | 改用 multicast 失去 batch 優勢；可選擇性對「重點客戶」用 push |
| LIFF 整合：點擊後解析 userId | 需要 LIFF endpoint + LINE Login，工程量大 |
| Click 時間序列圖表（每 5 分鐘一個 bucket） | UI 加分項 |
| 防 Bot：rate limit / referer 檢查 | 上線前才需要 |
| 轉換漏斗（click → 下單 → 付款） | 需要落地頁回拋給後端的整合 |
| 排程批次 click_count 重算（修正偏差） | 對帳項，視實際需求 |

---

## 十、累計 commit 樹（含 Phase 7）

```
{COMMIT_PLACEHOLDER}  feat: Phase 7 — Click Tracking、CTR、A/B 用點擊率比較
abb58cb               feat: Phase 6 — 加分項全集（排程 + 權限 + Narrowcast + A/B）
dd6b514               feat: Phase 5 — Flex Message 預覽、預設模板庫、匯入/匯出
eefc99e               feat: Phase 4 — SSE 即時進度、成效統計、失敗清單、PEL dead-letter
b1d95c4               chore: 新增 repo 層級 .gitignore 並提交 frontend lockfile
92e3000               feat: Phase 3 — 高併發核心（Redis Stream + Worker Pool + Token Bucket）
ca6b49c               feat: Phase 2 — 模板與簡單推播主流程
337e041               feat: Phase 1 — LINE 用戶資料與標籤管理
```
