# Phase 1 完成總結 — 推播基礎建設

> 完成日期：2026-05-25
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 範圍：LINE 用戶資料表、Follow Webhook 自動建檔、標籤 CRUD、用戶貼標籤（單筆與批量）

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| 建立 `line_users` 資料表 | ✅ | MySQL + Postgres 雙版本 V4 migration |
| Follow webhook 觸發建檔 | ✅ | 同步抓 LINE Profile API 補完暱稱、頭像 |
| Unfollow webhook 標記 BLOCKED | ✅ | 保留歷史資料、記錄 `unfollowed_at` |
| 訊息事件兜底建檔 | ✅ | 用戶若未經 Follow 直接傳訊也會自動建檔 |
| 標籤 CRUD | ✅ | 名稱唯一、HEX 顏色驗證、用戶數反正規化 |
| 用戶單筆貼標籤 | ✅ | 覆寫式（以傳入清單為準） |
| 用戶批量貼/移除標籤 | ✅ | `BulkTagRequest` 支援 ADD / REMOVE |

---

## 二、新增與修改檔案

### 後端

| 類別 | 檔案 |
|------|------|
| Migration | `backend/src/main/resources/db/migration/mysql/V4__broadcast_phase1.sql` |
| Migration | `backend/src/main/resources/db/migration/postgres/V4__broadcast_phase1.sql` |
| Entity | `backend/src/main/java/com/linechatbot/model/entity/LineUser.java` |
| Entity | `backend/src/main/java/com/linechatbot/model/entity/Tag.java` |
| Repository | `backend/src/main/java/com/linechatbot/repository/LineUserRepository.java` |
| Repository | `backend/src/main/java/com/linechatbot/repository/TagRepository.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/TagDTO.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/LineUserDTO.java` |
| DTO | `backend/src/main/java/com/linechatbot/model/dto/BulkTagRequest.java` |
| Service | `backend/src/main/java/com/linechatbot/service/LineUserService.java` |
| Service | `backend/src/main/java/com/linechatbot/service/TagService.java` |
| Controller | `backend/src/main/java/com/linechatbot/controller/TagController.java` |
| Controller | `backend/src/main/java/com/linechatbot/controller/LineUserController.java` |
| 修改 | `backend/src/main/java/com/linechatbot/controller/LineWebhookController.java`（新增 Follow / Unfollow / message 兜底處理） |
| 修改 | `backend/src/main/java/com/linechatbot/exception/GlobalExceptionHandler.java`（新增 `IllegalArgumentException` 處理） |

### 前端

| 類別 | 檔案 |
|------|------|
| Types | `frontend/src/types/index.ts`（新增 Tag / LineUser / BulkTagRequest 等型別） |
| API | `frontend/src/api/tags.ts` |
| API | `frontend/src/api/lineUsers.ts` |
| 元件 | `frontend/src/components/Tags/TagChip.tsx` |
| 元件 | `frontend/src/components/Tags/TagPicker.tsx` |
| 頁面 | `frontend/src/pages/TagManagement.tsx` |
| 頁面 | `frontend/src/pages/LineUsers.tsx` |
| 修改 | `frontend/src/App.tsx`（新增 `/line-users`、`/tags` 路由） |
| 修改 | `frontend/src/components/Layout/Sidebar.tsx`（新增 LINE 用戶 / 標籤管理 兩個選單） |

---

## 三、資料表結構

```
line_users
├── id BIGINT PK
├── line_user_id VARCHAR(100) UNIQUE
├── display_name, picture_url, status_message, language
├── status VARCHAR(20)            -- FOLLOWED / BLOCKED
├── followed_at, unfollowed_at, last_message_at
├── created_at, updated_at
└── INDEX(status), INDEX(followed_at)

tags
├── id BIGINT PK
├── name VARCHAR(50) UNIQUE
├── color VARCHAR(7) DEFAULT '#1677ff'
├── description VARCHAR(200)
├── user_count INT DEFAULT 0      -- 反正規化欄位
├── created_by BIGINT FK users
└── created_at, updated_at

user_tags
├── line_user_id BIGINT FK line_users  ON DELETE CASCADE
├── tag_id BIGINT FK tags              ON DELETE CASCADE
├── tagged_at, tagged_by
├── PRIMARY KEY (line_user_id, tag_id)
└── INDEX(tag_id, line_user_id)
```

---

## 四、API 端點

| Method | Path | 用途 |
|--------|------|------|
| GET | `/api/tags` | 列出所有標籤 |
| POST | `/api/tags` | 新增標籤 |
| GET | `/api/tags/{id}` | 查詢單一標籤 |
| PUT | `/api/tags/{id}` | 修改標籤 |
| DELETE | `/api/tags/{id}` | 刪除標籤（連動清除 user_tags） |
| GET | `/api/line-users` | 分頁查詢（`keyword`、`status`、`tagIds`） |
| GET | `/api/line-users/{id}` | 查詢單一用戶 |
| POST | `/api/line-users/{id}/tags` | 指派標籤（覆寫式） |
| POST | `/api/line-users/bulk-tag` | 批量 ADD/REMOVE 標籤 |

---

## 五、關鍵設計決策

### 1. `user_count` 反正規化欄位
- 標籤的「用戶數」直接存在 `tags.user_count` 欄位，每次貼 / 移除標籤後由 `TagRepository.refreshUserCount(tagIds)` 重算。
- **目的：** 列表顯示用戶數時避免 `COUNT(*)` 全掃 `user_tags`；大規模時這個查詢會變很慢。

### 2. Follow Profile 抓取「失敗不阻塞」
- `LineUserService.enrichProfile()` 包在 try-catch 內，失敗時只 `log.warn` 不拋例外。
- **目的：** LINE Profile API 偶爾抖動或用戶設定隱私時不應阻擋 Follow 事件處理。

### 3. Webhook 三類事件兜底
- Follow / Unfollow / Message 三類都會建檔；訊息事件作為 fallback 確保不漏建。
- **目的：** 真實環境中事件偶有掉失，三層兜底保證用戶資料完整性。

### 4. `ON DELETE CASCADE` 連動清除
- `user_tags` 設定當 LineUser 或 Tag 被刪除時自動清除關聯。
- **目的：** 避免孤兒記錄；尤其是標籤刪除時不需手動清理。

### 5. 覆寫式 vs 增量式標籤指派
- 單筆指派 `POST /line-users/{id}/tags` 是覆寫式（以傳入 tagIds 為準）。
- 批量操作 `POST /line-users/bulk-tag` 才支援 ADD / REMOVE 兩種模式。
- **目的：** 單筆編輯介面通常是「勾選清單」UX，覆寫式較直觀；批量則明確區分意圖。

---

## 六、驗證結果

- ✅ 後端 `mvn -o compile` 通過
- ✅ 前端 `npx tsc --noEmit` 通過

---

## 七、尚未完成（後續 Phase 處理）

- 標籤建立時記錄 `created_by`（目前 service 未從 SecurityContext 帶入）—— 推播功能需要稽核時補上
- 排程性的「全量 LINE Profile 同步」（目前只在事件時抓）
- 用戶詳情頁（看單一用戶的訊息歷史、標籤變更紀錄）

---

## 八、下一階段預告

**Phase 2 — 模板與簡單推播**
- MessageTemplate 資料表與 CRUD
- 簡單 TEXT 推播主流程跑通
- BroadcastTask / BroadcastChunk 資料表
- multicast 500 人/批的自管分批發送（同步版本，先不引入 Queue 與 Worker Pool）
