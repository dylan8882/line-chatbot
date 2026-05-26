# 除錯紀錄：LazyInitializationException 與 Redis Stream BLOCK timeout

> 發生於：2026-05-27（Phase 7 完成、啟動後測試 OA 訊息與後台用戶查詢）
>
> 兩個獨立 bug，啟動後即可觀察到：
> - **Bug B**：後台查 LINE 用戶清單 → 500 + `LazyInitializationException`
> - **Bug C**：backend.log 每 5 秒噴 4 條 `Redis command timed out`（4 個 worker 各一）

---

## Bug B：`LazyInitializationException`：could not initialize proxy - no Session

### 症狀

呼叫 `GET /api/line-users` 後，後端日誌：

```
ERROR c.l.exception.GlobalExceptionHandler  : 未預期錯誤：
org.hibernate.LazyInitializationException: failed to lazily initialize a collection of role:
com.linechatbot.model.entity.LineUser.tags: could not initialize proxy - no Session
    at AbstractPersistentCollection.throwLazyInitializationException(...)
    at ... LineUserService.toDTO(...)
```

前端收到 500，畫面上不顯示用戶清單。

### 為什麼發生

#### LAZY fetch 的本質

```java
// LineUser.java
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
        name = "user_tags",
        joinColumns = @JoinColumn(name = "line_user_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
)
private Set<Tag> tags = new HashSet<>();
```

Hibernate `LAZY` 的意思：「**查 LineUser 時不撈 tags，等真的有人 `.getTags()` 才另發一次 SQL**」。但這「另發 SQL」必須在**同一個 Hibernate Session（≈ JPA EntityManager）內**才能執行。

#### Session 的邊界

Spring Data JPA 預設只在 `@Transactional` 方法執行期間開啟 session，方法結束 session 就關閉。我們的 `LineUserService.search()` 原本沒有 `@Transactional`：

```java
// 修之前（出 bug）
public Page<LineUserDTO> search(...) {
    return lineUserRepository.search(...).map(this::toDTO);
    //      ↑ repository call: 內部開 session 撈 LineUser、結束關 session
    //                          ↑ session 已關
    //                                              ↑ toDTO 內 user.getTags() → 沒 session 可用 → 爆
}
```

#### 為什麼沒走 OSIV (Open Session In View) 自動接住？

Spring Boot 預設啟用 OSIV（讓 session 活到 HTTP response 寫完為止），但我們專案在 `application.yml` 明確關掉：

```yaml
spring:
  jpa:
    open-in-view: false   # 我們專案明確關閉
```

關掉 OSIV 是正確的做法（避免 N+1 query、避免 controller 層意外觸發 lazy load、效能與正確性都比較好）。但代價是：**所有 lazy 屬性的 access 都必須在 `@Transactional` 範圍內完成**。

### 修法

對 service 的查詢方法加 `@Transactional(readOnly = true)`，讓整個方法（包含 DTO mapping 與其中的 `user.getTags()`）都在同一個 session 內：

```java
// LineUserService.java（修後）
@Transactional(readOnly = true)
public Page<LineUserDTO> search(String keyword, String status, List<Long> tagIds, Pageable pageable) {
    ...
    return lineUserRepository.search(kw, st, tags, pageable).map(this::toDTO);
}

@Transactional(readOnly = true)
public LineUserDTO getById(Long id) {
    return lineUserRepository.findById(id)
            .map(this::toDTO)
            .orElseThrow(() -> new ResourceNotFoundException("LineUser", id));
}
```

`readOnly = true` 給 Hibernate 提示「這 transaction 不會寫」，可優化（不必 flush dirty checking）。功能上跟 `@Transactional` 等同。

### 為什麼不選其他方案

| 方案 | 缺點 |
|------|------|
| **改 EAGER fetch** (`@ManyToMany(fetch = EAGER)`) | 每次撈 LineUser 都會 JOIN tags，連不需要的場景也會多撈。LAZY 是更好的預設 |
| **再開 OSIV** (`open-in-view: true`) | 全域影響、容易養成 controller 層意外觸發 lazy load 的壞習慣、N+1 query 風險高 |
| **JOIN FETCH** 在 repository query 加 | 適合特定 query（例如 search），但要每個方法手動寫；現在用 `@Transactional` 更通用 |
| **在 controller 加 `@Transactional`** | 違反分層原則（transaction 應該由 service 控制） |

### 預防

**判斷準則：** service 中只要會走 lazy 關聯（`@OneToMany`、`@ManyToMany` 的預設）就要 `@Transactional`。寫一個查詢方法時自問：「有沒有在 entity 之外（DTO mapping、回傳 controller 後）access 任何 collection？」

我們專案受影響的還有哪些？

- `BroadcastService.list / getDetail` — 用的是 `BroadcastTask`，沒有 lazy collection（chunks 是另外 query 進來，不是 JPA 關聯）→ OK
- `MessageTemplateService` / `TagService` — 沒 lazy collection → OK
- `LineUserService` — 有 `tags`（已修）✅

未來新增的 entity 若有 lazy collection，service 查詢時記得加 `@Transactional`。

---

## Bug C：Worker 一直噴 `Redis command timed out`

### 症狀

每 5 秒在 backend.log 看到 4 條（4 個 worker）：

```
ERROR c.l.service.BroadcastWorkerManager  : Worker worker-0 迴圈例外：Redis command timed out
org.springframework.dao.QueryTimeoutException: Redis command timed out
    at LettuceExceptionConverter.convert(...)
    ...
    at BroadcastQueueService.readNext(BroadcastQueueService.java:117)
    at BroadcastWorkerManager.runLoop(BroadcastWorkerManager.java:62)
Caused by: io.lettuce.core.RedisCommandTimeoutException: Command timed out after 5 second(s)
```

系統功能不受影響，但 log 持續被洗，難看且容易混淆真正的問題。

### 為什麼發生

#### XREADGROUP BLOCK 的語意

Worker 用 Redis Stream 的 `XREADGROUP BLOCK 5000` 命令來 long-poll 拿訊息：

> 「Stream 沒新訊息就**阻塞 5 秒**等，期間若有新訊息會立刻回傳；沒等到就回傳空。」

正常情境：閒置時每 5 秒回一次空，worker 立刻再發下一次。

#### 衝突：Lettuce 的 commandTimeout

Lettuce 是 Spring Data Redis 預設的客戶端，每個 Redis 命令都有全域 `commandTimeout`（多久沒回應就視為失敗）。我們 `RedisConfig` 設成：

```java
LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
        .poolConfig(poolConfig)
        .commandTimeout(Duration.ofSeconds(5))   // ← 5 秒
        .build();
```

對於一般命令（GET / SET / INCR）這設定很合理 — 沒回應超過 5 秒一定有問題。

但對 BLOCK 命令：

```
worker 發 XREADGROUP BLOCK 5000ms
   ↓
Redis server 等 5 秒沒新訊息 → 準備回空
   ↓ 同時間
Lettuce client 的 5 秒 commandTimeout 觸發 → 拋 RedisCommandTimeoutException
   ↓
Worker catch 到 Exception → 噴 ERROR log
```

兩個 5 秒同時到，client 沒辦法區分「BLOCK 完成回空」vs「server 真的當了」。

#### 為什麼之前 Phase 3 就有這設計卻沒爆？

Phase 3 剛寫好時可能沒實際跑滿一個閒置週期，或剛好 stream 有訊息一直被處理，沒卡到全空閒 5 秒的場景。Phase 7 啟動後系統全空閒，4 個 worker 都進入 BLOCK 狀態，timeout 同步觸發，被立刻發現。

### 修法

#### 雙層保險

**1. 把 BLOCK 時間縮到 4 秒** — 讓 server 一定先回應，client 不會 timeout：

```java
// BroadcastWorkerManager.java
@Value("${broadcast.workers.block-ms:4000}")
private long blockMs;
```

留 1 秒 buffer 給網路 round-trip。

**2. 對 `QueryTimeoutException` 單獨 catch、用 debug log** — 萬一未來又踩到（例如 Lettuce 升級、Redis 慢），不會洗 log：

```java
private void runLoop(String workerId) {
    while (running.get()) {
        try {
            MapRecord<String, Object, Object> record = queueService.readNext(workerId, blockMs);
            if (record == null) continue;
            ...
        } catch (QueryTimeoutException timeout) {
            // BLOCK 等空時的正常邊界情況，不噴 ERROR
            log.debug("Worker {} XREADGROUP timeout，重試", workerId);
        } catch (Exception e) {
            log.error("Worker {} 迴圈例外：{}", workerId, e.getMessage(), e);
            sleep(500);
        }
    }
}
```

### 為什麼不調大 commandTimeout

第一直覺：把 `commandTimeout` 改 10 秒不就好了？

不行：

1. **全域影響** — 所有 Redis 命令都會等 10 秒才認失敗。一般命令（GET / SET）正常 < 50ms 就回，等 10 秒只會讓真出問題時遲遲不暴露
2. **無法區分** — Redis 真的卡了 vs BLOCK 等空，從 client 角度看都是「沒回應」
3. **滑坡** — 改 10s 之後也許之後 BLOCK 設 9s 又卡到，每次踩雷都調大 timeout 是壞循環

**正解就是讓 BLOCK 時間嚴格小於 commandTimeout**，永遠保證 server 先回應。

### 其他可能的修法（為什麼沒選）

| 方案 | 細節 | 為什麼沒選 |
|------|------|----------|
| **為 stream 連線設獨立 LettuceConnectionFactory** | 給 worker 一條 commandTimeout=60s 的專用連線 | 程式碼增加複雜度（兩條 Redis 連線管理）；目前需求用不到 |
| **改用 reactive Redis API** | `ReactiveStreamOperations` 對 BLOCK 有更好處理 | 整個專案目前是 imperative API，引入 reactive 跨範式 |
| **每次 BLOCK 1 秒**（縮更短） | block-ms=1000、跑得更頻繁 | 變相 polling、CPU / 網路浪費；4s 已經夠用 |

---

## 修改摘要

| 檔案 | 變更 |
|------|------|
| `backend/src/main/java/com/linechatbot/service/LineUserService.java` | `search()` 與 `getById()` 加 `@Transactional(readOnly = true)` |
| `backend/src/main/java/com/linechatbot/service/BroadcastWorkerManager.java` | `block-ms` 預設改 4000；`runLoop` 加 `catch (QueryTimeoutException)` |

驗證：`mvn -o compile` 通過。

重啟 backend 後：
- 後台 `/line-users` 不再爆 500
- backend.log 不再每 5 秒噴 worker timeout error

---

## 一般教訓

1. **JPA LAZY + 關掉 OSIV** 需要 service 層完整覆蓋 `@Transactional`。若哪個 entity 有 lazy collection 但不希望 service 都包 transaction，可在該 entity 用 `@ElementCollection` 或改 EAGER。我們選 LAZY + `@Transactional` 路線。
2. **任何 long-poll / BLOCK / 長時 IO 命令** 都要確認 client timeout > server 等待時間。預留至少 20-25% buffer。
3. **catch Exception 時要分類** — 系統正常運作的「邊界情況例外」（如 BLOCK timeout）用 debug log；真異常才 error log。否則 log 會被洗到看不到重要資訊。

---

## 參考

- [Hibernate 6 User Guide — Sessions and transactions](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html)
- [Spring Boot 屬性：`spring.jpa.open-in-view`](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data)
- [Lettuce Reference — Client Configuration / Timeout](https://lettuce.io/core/release/reference/index.html#_client_options)
- [Redis Streams — XREADGROUP](https://redis.io/docs/latest/commands/xreadgroup/)
