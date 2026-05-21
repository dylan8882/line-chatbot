# ngrok 安裝與 LINE OA Webhook 串接流程

本文件記錄從安裝 ngrok 到 LINE OA 串接完成的完整步驟，適用於本機開發環境（WSL/Linux）。

---

## 前置條件

- 後端已可正常啟動（`dev.sh` 或手動啟動），預設跑在 port 8080
- 已有 LINE Developers 帳號並建立 Messaging API Channel
- 已在專案管理後台完成 LINE Channel 設定（Channel ID、Channel Secret、Channel Access Token）

---

## 1. 安裝 ngrok

### 加入 apt 套件來源

```bash
# 下載並信任 ngrok GPG 簽章金鑰
curl -sSL https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null

# 加入 ngrok apt 來源
echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | sudo tee /etc/apt/sources.list.d/ngrok.list

# 更新並安裝
sudo apt update && sudo apt install ngrok
```

### 確認安裝成功

```bash
ngrok version
# 應顯示：ngrok version 3.x.x
```

---

## 2. 綁定 ngrok 帳號

前往 [ngrok.com](https://ngrok.com) 登入 → 左側選單 **Your Authtoken** → 複製 token

```bash
ngrok config add-authtoken <你的_authtoken>
```

---

## 3. 啟動 tunnel

確認後端已在運行後，開啟新的 terminal 執行：

```bash
ngrok http 8080
```

成功後會顯示：

```
Forwarding  https://xxxx.ngrok-free.app -> http://localhost:8080
```

記下這個 HTTPS 網址，每次重啟 ngrok 網址會改變，需重新設定 LINE 後台。

---

## 4. 設定專案管理後台

進入前端管理後台 → **LINE 串接設定**：

- **伺服器 Base URL**：填入 ngrok 網址（不含 `/webhook`）
  ```
  https://xxxx.ngrok-free.app
  ```
- 儲存後，頁面會自動顯示完整 **Webhook URL**：
  ```
  https://xxxx.ngrok-free.app/webhook
  ```

---

## 5. 設定 LINE Developers Console

前往 [LINE Developers Console](https://developers.line.biz/) → 選擇你的 Channel → **Messaging API**：

1. **Webhook URL** 填入：
   ```
   https://xxxx.ngrok-free.app/webhook
   ```
2. 點擊 **Update** 儲存
3. 點擊 **Verify** 驗證（應回傳 200 成功）
4. 開啟 **Use webhook** 開關

---

## 6. 測試串接

1. 在 LINE Developers Console 找到 QR Code，用 LINE 掃描加入 OA 為好友
2. 傳一則文字訊息給 OA
3. 確認後端 log 出現：
   ```
   收到 LINE 訊息：userId=xxx, message=xxx
   ```

有出現 log 代表 webhook 串接成功。

---

## 注意事項

- ngrok 免費版每次重啟網址會變更，需重新更新 LINE Developers Console 的 Webhook URL
- webhook 路徑為 `/webhook`，不是 `/api/line/webhook`
- LINE 只會打後端（port 8080），前端（port 5173）與 webhook 無關
- 若 Verify 失敗，先確認後端是否正常運行、ngrok tunnel 是否還在線
