# Phase 5 完成總結 — Flex 編輯器

> 完成日期：2026-05-26
>
> 對應設計文件：[broadcast-feature-design.md](./broadcast-feature-design.md)
>
> 上一階段：[Phase 4 — 進度與成效](./broadcast-phase4-summary.md)
>
> 範圍：Flex Message 即時預覽、預設模板庫、模板匯入 / 匯出、模板與推播建立頁整合

---

## 一、目標達成情況

| 設計目標 | 狀態 | 備註 |
|---------|------|------|
| Flex Message JSON 即時預覽 | ✅ | 自製渲染器，近似 LINE App 風格 |
| 預設模板庫（4 種起跳） | ✅ | 簡單文字 + 問候、商品、優惠券、活動共 5 個 preset |
| 模板匯入（從本地 JSON 檔） | ✅ | 前端解析 / 校驗 / 直接填入 |
| 模板匯出（下載 JSON 檔） | ✅ | 一鍵下載當前 JSON |
| 訊息模板頁整合 | ✅ | 編輯 Modal 改用 FlexEditor 雙欄佈局 |
| 推播建立頁整合 | ✅ | 訊息來源（模板 / 自訂）旁邊有即時預覽 |
| 拖拉式視覺編輯器 | ⏭️ | Phase 5 暫不做（範圍大、改為連結到 LINE Flex Simulator） |

---

## 二、新增與修改檔案

### 前端（Phase 5 純前端）

| 類別 | 檔案 | 變更 |
|------|------|------|
| 元件 | `components/FlexEditor/FlexPreview.tsx` | 新增（LINE 風格 Flex 渲染器） |
| 元件 | `components/FlexEditor/PresetPicker.tsx` | 新增（預設模板選擇器，含縮圖預覽） |
| 元件 | `components/FlexEditor/FlexEditor.tsx` | 新增（JSON 編輯 + 即時預覽 + 預設/匯入/匯出工具列） |
| 資料 | `components/FlexEditor/presets.ts` | 新增（5 種預設模板的 LINE messages JSON） |
| 頁面 | `pages/MessageTemplates.tsx` | 改：編輯 Modal 改用 FlexEditor，移除舊 textarea 與 jsonError state |
| 頁面 | `pages/BroadcastCreate.tsx` | 改：訊息內容區改為雙欄（輸入 + 預覽） |

---

## 三、FlexPreview 渲染能力

| LINE Flex 元素 | 渲染支援 | 備註 |
|---------------|---------|------|
| 訊息類型 `text` | ✅ | 聊天泡泡樣式 |
| 訊息類型 `image` | ✅ | 圖片泡泡 |
| 訊息類型 `flex` | ✅ | Bubble / Carousel |
| Bubble sections | ✅ | header / hero / body / footer + styles.backgroundColor |
| Box layout vertical / horizontal | ✅ | flex column / row |
| Box layout baseline | ✅ | align-items: baseline |
| Text | ✅ | size / weight / color / align / wrap / flex |
| Image | ✅ | url / aspectRatio / aspectMode（cover / fit） |
| Button | ✅ | style（primary / secondary / link）+ color |
| Separator | ✅ | color |
| Spacer | ✅ | size → px |
| Icon | ✅ | url + size |
| Filler | ✅ | flex: 1 撐開空間 |
| 圖片載入失敗 | ✅ | 自動隱藏 / 顯示佔位 |

支援的 size tokens：nano / xxs / xs / sm / md / lg / xl / xxl / 3xl / 4xl / 5xl，
mapping 到 11–40 px。Spacing / padding tokens：none / xs / sm / md / lg / xl / xxl 對應 0–24px。

> 不追求 100% 還原（LINE 客戶端 padding / line-height 有細微差異），
> 目標：讓後台使用者「大致看到內容長相」並能在送出前確認。

---

## 四、預設模板清單

| Key | 名稱 | 類型 | 主要元素 |
|-----|------|------|----------|
| `simple-text` | 簡單文字 | TEXT | 單一段文字 |
| `greeting` | 問候 | FLEX | hero 圖片 + 標題 + 內文（節慶問候） |
| `product` | 商品介紹 | FLEX | hero 圖片 + 名稱 + 價格（NT$ 標示） + 購買按鈕 |
| `coupon` | 優惠券 | FLEX | 橙色主題 + 折扣標題 + 代碼 + 立即使用按鈕 |
| `event` | 活動公告 | FLEX | hero 圖片 + 標題 + 日期/地點/時間欄位 + 報名 + 查看更多按鈕 |

圖片使用 `picsum.photos` placeholder，正式使用時請替換成自有 CDN。

---

## 五、FlexEditor 操作流程

```
┌──────────────────────────────────────────────────────────────────┐
│ Toolbar: [載入預設] [匯入 JSON] [匯出 JSON] [Flex Simulator ↗]   │
├──────────────────────────────────────┬───────────────────────────┤
│ JSON 編輯（textarea，monospace）     │ 即時預覽（FlexPreview）   │
│                                      │                           │
│ - 邊輸入邊驗證                       │ - 解析失敗時顯示空狀態    │
│ - JSON 格式錯誤 → 紅色 Alert         │ - 圖片載入失敗自動處理    │
│ - 必須為陣列、≤5 則                  │ - 支援 carousel 橫向卷    │
│                                      │                           │
└──────────────────────────────────────┴───────────────────────────┘
```

**載入預設**：點按鈕開 PresetPicker Modal，每個 preset 卡片本身也顯示縮圖預覽，
點選後直接填回編輯器並同步切換 messageType 欄位（如 FLEX → 自動切到 FLEX）。

**匯入 JSON**：本地檔案前端 FileReader 解析，驗證為陣列後填回；不會真的上傳。

**匯出 JSON**：將編輯器目前內容打包為 Blob 下載，檔名 `line-message-{timestamp}.json`。

---

## 六、關鍵設計決策

### 1. 自製渲染器而非引用第三方
- LINE 沒有官方 React 元件可直接用；現有 npm 套件（`react-line-flex-message` 等）維護不活躍且追隨 spec 進度差。
- 自製只需要支援我們關心的子集（不必涵蓋 spec 所有邊角），檔案 ~250 行可控。
- **缺點：** 不會 1:1 像 LINE App，差異在於精確 padding / line-height / icon font。

### 2. PresetPicker 卡片內嵌縮圖預覽
- 直接用同一個 `FlexPreview` 元件在每個 preset 卡片內渲染。
- 使用者選擇時就能看到效果，比文字描述直觀很多。
- **代價：** Modal 開啟時要渲染 5 個小型 bubble，但每個都是輕量 HTML，沒明顯效能問題。

### 3. 匯入 / 匯出走純前端 FileReader
- 不需要新增後端 API。
- 匯入時做基本驗證（必須是 JSON 陣列），其餘交給編輯器內部驗證。
- 配合既有 service 端的格式檢查（≤ 5 則訊息、非空陣列），兩層防護。

### 4. 載入預設時同步切換 messageType
- FlexEditor 暴露 `onPresetTypeChange` callback，由 `FlexEditorField` wrapper 接住並 `form.setFieldValue('messageType', type)`。
- 使用者體驗：選了 FLEX 模板就自動把類型切到 FLEX，不用手動再選。

### 5. BroadcastCreate 的預覽是 readonly 雙欄
- 不嵌完整 FlexEditor 是因為輸入區仍是 Form 控制的 textarea，預覽只需要看不需要編。
- 同時兼顧「使用模板」與「自訂 JSON」兩種來源 — useMemo 把它們抽象成同一個 `previewContent`。

### 6. 暫不做拖拉式視覺編輯器
- 拖拉式 UI 工程量大（state 同步、撤銷重做、結構轉換），ROI 對 side project 不高。
- 折衷：明顯放上 LINE 官方 Flex Simulator 連結，使用者可在那編好複製 JSON 過來，再用 FlexEditor 即時預覽 / 匯入。

---

## 七、驗證結果

- ✅ `npx tsc --noEmit` 通過
- ✅ 後端無變更，編譯狀態維持 Phase 4 結果
- 手動驗證項目（需啟動服務）：
  - 從預設載入 5 種模板各自能正確預覽
  - 匯入 / 匯出 JSON 來回後內容一致
  - BroadcastCreate 切換 TEMPLATE / CUSTOM 預覽即時更新

---

## 八、尚未完成（後續 Phase 處理）

| 項目 | 規劃階段 |
|------|---------|
| 拖拉式視覺編輯器（Bubble / Box / Text / Image / Button 拖放） | Phase 6 加分項或獨立任務 |
| 預設模板從 DB 取得（管理員可新增模板庫） | Phase 6 加分項 |
| Narrowcast API、A/B 測試、排程推播 | Phase 6 |

---

## 九、本階段技術亮點（面試展示）

1. **自製 LINE Flex 渲染器** —— 不依賴第三方套件，遞迴渲染處理 box / text / image / button / separator / spacer / icon / filler 等元素，支援 carousel 橫向多 bubble。
2. **即時預覽雙欄編輯** —— JSON 改完即看效果，類似 Markdown Editor 的 UX 模式。
3. **預設模板含縮圖** —— PresetPicker 直接複用 FlexPreview 元件，避免維護兩套渲染邏輯。
4. **前端純檔案 import/export** —— FileReader + Blob 一氣呵成，零後端負擔。
5. **Form.Item 受控 wrapper pattern** —— `FlexEditorField` 把 form context 與外部元件 props 解耦，載入預設時同步更新其他欄位。
6. **務實的範圍取捨** —— 拖拉式編輯器明知 ROI 低就跳過，明確標示連結到官方 Simulator 作為替代方案。

---

## 十、下一階段預告（如有要做）

**Phase 6 — 加分項（選做）**
- **Narrowcast API**：LINE 官方大規模推播（>50k 用戶）走 narrowcast，搭配 Audience API
- **A/B 測試**：同一任務切兩組 audience，比較成效
- **排程推播**：scheduled_at 已預留欄位，加 @Scheduled poller 自動觸發
- **權限細分**：role-based 細控誰能建立 / 提交推播
- **拖拉式 Flex 編輯器**：若仍想做
