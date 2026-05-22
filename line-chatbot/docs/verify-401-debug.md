# verify 連線 401 問題排查紀錄

## 問題描述

前端 LINE 串接設定頁面點擊「驗證連線」按鈕，出現：
```
lineSettings.ts:19  POST http://localhost:5173/api/line-settings/verify 403 (Forbidden)
```

重新登入後再點，改為：
```
POST http://localhost:5173/api/line-settings/verify 401 (Unauthorized)
```
並自動導回登入頁，登入後仍重複發生。

---

## 排查過程

### 第一個錯誤：403

**初步假設**：LINE Bot SDK 的 `LineBotServerInterceptor` 攔截了所有 POST 請求。

**調查方式**：讀取 SDK jar bytecode 字串，確認攔截器行為。

**結論**：
- 攔截器只在方法有 `@LineBotMessages` 參數時才驗證簽章，沒有的話直接 pass through
- 攔截器失敗時回傳的是 `BAD_REQUEST`（400），不是 403
- 403 是 Spring Security 在沒有 `AuthenticationEntryPoint` 的情況下，對未認證請求的**預設行為**

**根因**：JWT 過期或失效時，`JwtAuthenticationFilter` 不設定 authentication，Spring Security 找不到認證，預設回傳 403（而非 401）。前端 `client.ts` 只攔截 401 做自動登出，403 沒有被處理，使用者看到錯誤訊息但不會被導向登入頁。

**修正**：在 `SecurityConfig` 加入 `AuthenticationEntryPoint`，明確回傳 401：

```java
.exceptionHandling(ex -> ex
    .authenticationEntryPoint((request, response, authException) ->
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
)
```

---

### 第二個錯誤：401（登入後仍發生）

加入 `AuthenticationEntryPoint` 後，403 變成 401，前端也正確導向登入頁。但重新登入取得新 token 後，再點驗證連線仍然 401。

**JWT 應有效**，但還是被攔截，代表問題不在 JWT 本身。

**調查方向**：檢查 Controller 與 Service 的實作。

```java
// Controller（原始）
@PostMapping("/verify")
public Mono<ResponseEntity<Map<String, Object>>> verifyToken() {
    return lineSettingsService.verifyAccessToken()
            .map(result -> ResponseEntity.ok(...));
}

// Service（原始）
public Mono<String> verifyAccessToken() {
    return configRepository.findById(CONFIG_ID)
            ...
            .map(c -> webClientBuilder.build()
                    .retrieve()
                    .bodyToMono(String.class)
                    ...
            )
            ...
}
```

**根因**：Spring MVC 與 Reactive（Mono）混用導致 SecurityContext 丟失。

---

## 根本原因詳解：Spring MVC + Mono 的 SecurityContext 問題

### Spring Security 的 SecurityContext 儲存機制

Spring Security 把認證資訊存在 `ThreadLocal` 裡：

```
request 進來
  → JwtAuthenticationFilter 驗證 JWT
  → 把認證資訊存入 ThreadLocal（SecurityContext）
  → 後續同一個 thread 上的程式碼都能取得認證資訊
```

`ThreadLocal` 的特性：**只在同一條 thread 上有效**，換 thread 就看不到了。

### 問題發生的完整流程

```
1. 請求進來（Thread A）
   → JwtAuthenticationFilter 驗證 JWT ✓
   → 把認證資訊存進 Thread A 的 ThreadLocal

2. 進入 verifyToken()（還在 Thread A）
   → 回傳 Mono<ResponseEntity<...>>
   → Spring MVC 說：「這是非同步的，交給 Reactor 處理」

3. Reactor 換到 Thread B 執行 Mono 的內容
   → Thread B 的 ThreadLocal 是空的（沒有認證資訊）

4. Mono 執行完畢，Spring MVC 準備寫回應
   → Spring Security 檢查 Thread B 的 ThreadLocal
   → 發現沒有認證
   → 觸發 AuthenticationEntryPoint → 回傳 401
```

### 為什麼 GET / PUT 正常但 POST /verify 不正常

因為 GET 和 PUT 的 Controller 方法回傳的是同步的 `ResponseEntity<...>`，整個處理流程都在同一個 thread 上，SecurityContext 不會丟失。

只有 `verifyToken()` 回傳 `Mono<ResponseEntity<...>>`，才會觸發 thread 切換。

---

## 修正方案

### 方案一（臨時）：Controller 用 `.block()` 強制同步

```java
@PostMapping("/verify")
public ResponseEntity<Map<String, Object>> verifyToken() {
    String result = lineSettingsService.verifyAccessToken().block(); // 強制等待，不切換 thread
    return ResponseEntity.ok(Map.of(...));
}
```

問題：Service 層的簽名還是 `Mono<String>`，語意上說「我是非同步的」，但 Controller 立刻把它變回同步，造成誤導。

### 方案二（最終）：整個呼叫鏈改為同步

**Service 層**：回傳 `String`，WebClient 的 `Mono` 在內部 `.block()`

```java
public String verifyAccessToken() {
    return configRepository.findById(CONFIG_ID)
            .filter(c -> StringUtils.hasText(c.getChannelAccessToken()))
            .map(c -> {
                try {
                    String body = webClientBuilder.build()
                            .get()
                            .uri(LINE_API_BASE + "/v2/bot/info")
                            .header("Authorization", "Bearer " + c.getChannelAccessToken())
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(10))
                            .block(); // Mono 在這裡結束，不往外傳
                    return "驗證成功：" + body;
                } catch (Exception e) {
                    log.warn("LINE Access Token 驗證失敗：{}", e.getMessage());
                    return "驗證失敗：" + e.getMessage();
                }
            })
            .orElse("尚未設定 Channel Access Token");
}
```

**Controller 層**：完全同步，乾淨

```java
@PostMapping("/verify")
public ResponseEntity<Map<String, Object>> verifyToken() {
    String result = lineSettingsService.verifyAccessToken();
    return ResponseEntity.ok(Map.of(
            "success", result.startsWith("驗證成功"),
            "message", result
    ));
}
```

---

## 關鍵知識點

### Mono 是什麼

`Mono` 是 Project Reactor 的型別，代表「未來會產生 0 或 1 個結果的非同步操作」。適合在 Spring WebFlux（全非同步架構）使用。

### 為什麼 WebClient 回傳 Mono

`WebClient` 是非同步 HTTP client，天生回傳 `Mono`。在 Spring MVC 環境使用 WebClient 完全沒問題，只是需要在適當的地方呼叫 `.block()` 把結果拉回同步。

### Spring MVC 不適合讓 Mono 往外傳

| | Spring MVC | Spring WebFlux |
|---|---|---|
| SecurityContext 儲存 | ThreadLocal（thread 切換就丟失）| Reactor Context（跟著 Mono 流動）|
| 適合回傳型別 | 同步 `ResponseEntity<...>` | `Mono<ResponseEntity<...>>` |

在 Spring MVC 裡讓 `Mono` 往外傳到 Controller 回傳型別，會造成 thread 切換後 SecurityContext 丟失，進而觸發非預期的 401。

### 若未來有非同步需求

如果需要真正的非同步好處（如同時推播大量用戶），可搭配 `@Async` + WebClient 的 `Flux` 批次發送，或考慮改用 Spring WebFlux 架構（需要整個後端一起改）。

---

## 修改的檔案

| 檔案 | 修改內容 |
|------|------|
| `SecurityConfig.java` | 新增 `AuthenticationEntryPoint`，JWT 失效時回傳 401 而非 403 |
| `LineSettingsService.java` | `verifyAccessToken()` 回傳型別從 `Mono<String>` 改為 `String` |
| `LineSettingsController.java` | `verifyToken()` 回傳型別從 `Mono<ResponseEntity<...>>` 改為 `ResponseEntity<...>`，移除 `.block()` |
