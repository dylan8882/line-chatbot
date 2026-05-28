# 除錯紀錄：點擊追蹤 `click_count` 不增加（雙層 bug）

> 發生於：2026-05-28（Feature C 收尾、實機 E2E 測試點擊追蹤）
>
> 症狀：LINE App 點擊推播訊息的 button → 正確跳轉到目標網站 → 後台
> `click_events` 表寫進新事件 → **但 `click_links.click_count` 數字不變**
>
> 表面像「累計失敗」，實際是 **兩個獨立 bug 疊加**：
> 1. Spring AOP **self-invocation** 繞 proxy → `@Async` / `@Transactional` 都失效
> 2. 修完 self-invocation 後，`@Async` 用的 executor pool **被 4 個 worker 無限迴圈永久占用** → 新任務只能 enqueue、不會執行

---

## 一、症狀觀察

使用者在 LINE App 點 button 2 次，期望後台統計：

```
LINE 點 button 2 次 → click_events 多 2 筆 + click_links.click_count = 2
```

實際得到：

```
click_events: ✅ 新增 2 筆（41, 42），記錄了 IP、UA
click_count : ❌ 仍是 0
```

backend.log 有 WARN：

```
WARN [nio-8080-exec-8] c.l.service.ClickTrackingService :
寫入 click_event 失敗：linkId=5, error=Executing an update/delete query
```

**第一個線索**：thread name 是 `nio-8080-exec-8`（Tomcat HTTP 工作執行緒），不是 `broadcast-worker-N`。

照原始碼，`recordAsync` 標了 `@Async("broadcastWorkerExecutor")`，應該跑在 broadcast-worker 上才對。**`@Async` 沒生效**。

---

## 二、Bug 1：Spring AOP self-invocation

### 原始碼（出 bug 版本）

```java
@Service
@RequiredArgsConstructor
public class ClickTrackingService {

    private final ClickLinkRepository linkRepository;
    private final ClickEventRepository eventRepository;

    public Optional<String> resolveAndRecord(String token, ...) {
        Optional<ClickLink> opt = linkRepository.findByToken(token);
        if (opt.isEmpty()) return Optional.empty();

        ClickLink link = opt.get();
        recordAsync(link.getId(), link.getTaskId(), ...);  // ❌ self-invocation
        return Optional.of(link.getTargetUrl());
    }

    @Async("broadcastWorkerExecutor")
    @Transactional
    public void recordAsync(Long linkId, ...) {
        eventRepository.save(...);
        linkRepository.incrementClickCount(linkId);  // 標 @Modifying 需要 outer TX
    }
}
```

### 為什麼 annotation 都失效

Spring AOP 透過 **proxy 物件**包裝 bean。當外部呼叫 `clickTrackingService.resolveAndRecord(...)`：

```
Controller → [Proxy(ClickTrackingService)] → 實際物件.resolveAndRecord(...)
                       ↑
                  Spring 攔截、套用 advice
```

但是在 `resolveAndRecord` 內部寫 `recordAsync(...)`，等同 `this.recordAsync(...)`：

```
實際物件.resolveAndRecord(...)
  └─ this.recordAsync(...)   ← 直接呼叫，沒經過 proxy
        → @Async  ❌ 不會切到非同步執行緒
        → @Transactional  ❌ 不會開交易
```

這就是「**self-invocation 繞 proxy**」，是 Spring AOP 最經典的陷阱。

### 為什麼 save 還成功、increment 卻失敗

兩個操作都在沒有 outer TX 的環境跑，差別在於 fallback：

| 操作 | 行為 | 沒 outer TX 的結果 |
|---|---|---|
| `eventRepository.save(...)` | Spring Data `SimpleJpaRepository.save()` 自帶 `@Transactional`，沒 outer 就自己開短交易 | ✅ 成功 |
| `linkRepository.incrementClickCount(...)` | 自訂 `@Modifying @Query("UPDATE ...")` JPQL，**JPA 規範要求** 必須有 outer TX 才能執行 `Query.executeUpdate()` | ❌ 抛 `TransactionRequiredException` |

最終 WARN log 的「Executing an update/delete query」就是 Hibernate / JPA 的標準錯誤訊息開頭（完整訊息會多一段「you need to use @Transactional or annotate the method with @Transactional」）。

而 `try/catch` 把例外吞掉只記 WARN，所以 redirect 仍然正常回 302，使用者表面看不到問題。

### Bug 1 的修法：拆獨立 bean

把實際工作搬到另一個 `@Service`，從 `ClickTrackingService` **跨 bean 呼叫**，proxy 才會包進來：

```java
@Service
@RequiredArgsConstructor
public class ClickEventWriter {  // ← 新 bean

    private final ClickEventRepository eventRepository;
    private final ClickLinkRepository linkRepository;

    @Async("broadcastWorkerExecutor")
    @Transactional
    public void recordEvent(Long linkId, Long taskId, ...) {
        eventRepository.save(...);
        linkRepository.incrementClickCount(linkId);
    }
}

@Service
@RequiredArgsConstructor
public class ClickTrackingService {

    private final ClickLinkRepository linkRepository;
    private final ClickEventWriter eventWriter;   // ← 注入新 bean

    public Optional<String> resolveAndRecord(...) {
        ...
        ClickLink link = opt.get();
        eventWriter.recordEvent(link.getId(), ...);  // ✅ 跨 bean 呼叫
        return Optional.of(link.getTargetUrl());
    }
}
```

跨 bean 的呼叫 `eventWriter.recordEvent(...)` 拿到的是 Spring 注入的 proxy，annotation 都能正常生效。

> **其他替代方案**：自我注入（`@Lazy ClickTrackingService self`）也能解，但讀起來比較怪。拆 bean 比較清楚、不會混淆。

---

## 三、Bug 2：executor 池被 worker 永久占用

修完 Bug 1，再測一輪——**click_count 還是 0**。但這次 backend.log 連 WARN 都沒有了。彷彿 `recordEvent` 根本沒執行。

### 第二個線索

寫個 curl 直接測：

```bash
$ curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8080/c/gKrs6fnIvMhm
HTTP 302
```

302 是對的，代表 `resolveAndRecord` 找到 link、回傳了 targetUrl。但 `click_events` 沒新增、`click_count` 不變、log 沒任何輸出。

→ 任務 **被 enqueue 但沒被執行**。

### 找出元兇

看 `BroadcastConfig` 的 executor 設定：

```java
@Bean(name = "broadcastWorkerExecutor")
public Executor broadcastWorkerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(100);
    ...
}
```

再看 `BroadcastWorkerManager` 啟動時做什麼：

```java
@PostConstruct
public void start() {
    for (int i = 0; i < workerCount; i++) {  // workerCount = 4
        int workerId = i;
        workerExecutor.execute(() -> runLoop(workerId));  // ← 跑一個無限迴圈
    }
}
```

`runLoop` 是 `while (running)` 的 Redis Stream 消費迴圈，每個 worker **永遠不會結束**。

### `ThreadPoolTaskExecutor` 的調度規則

關鍵設定組合：

| 設定 | 值 |
|---|---|
| corePoolSize | 4 |
| maxPoolSize | 16 |
| queueCapacity | 100 |

`ThreadPoolExecutor` 的調度順序：

```
1. 新任務進來：core threads 還沒滿（< 4）→ 開新 thread 跑
2. core threads 全占住、queue 還沒滿（< 100）→ 丟進 queue
3. queue 滿了、threads 還沒到 max（< 16）→ 才開額外 thread
4. queue 也滿、threads 也到 max → 觸發 rejected handler（CallerRunsPolicy）
```

**`broadcastWorkerExecutor` 的處境**：4 個 core threads 全被無限迴圈 worker 占住，永遠不釋放。任何 `@Async("broadcastWorkerExecutor")` 的新任務一定走到「step 2」進 queue。

Queue 容量 100，但 worker 永不結束 → queue 裡的任務**等同永遠不會被消費**，直到 queue 滿 100 觸發 CallerRunsPolicy（fallback 到呼叫者 thread 同步執行）。

我們的測試流量根本不到 100，於是 click event 任務全部**安靜地坐在 queue 裡死等**。沒拋例外、沒 log、沒任何外部訊號，因此 debug 起初困難。

### Bug 2 的修法：給 click event 獨立 executor

```java
@Bean(name = "clickEventExecutor")
public Executor clickEventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("click-evt-");
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
    ...
}
```

然後 `ClickEventWriter` 改用：

```java
@Async("clickEventExecutor")
@Transactional
public void recordEvent(...) { ... }
```

**為何不直接共用 `lineMessageExecutor`**：可以，但會讓 LINE Webhook 回覆與點擊事件搶同一個池。點擊事件爆量時可能延遲 Webhook 響應、影響 LINE 對 OA 的健康判定。獨立池雖然多了一個 bean，但 capacity 是嚴格隔離的，比較乾淨。

### 中間嘗試（學習紀錄）

修 Bug 2 的中間版本是先把 executor 換成 `lineMessageExecutor`（core=10, max=50, queue=1000）。實測能跑通，但作為知識掌握 + 設計品味考量，最終分出獨立的 `clickEventExecutor`。

---

## 四、為何兩個 bug 沒早一點被發現

點擊追蹤是 Phase 7 加入，當時 commit 含：
- `ClickLinkRewriter`（submit 時改寫 URL）
- `ClickTrackingService.recordAsync(...)` 同 bean 內呼叫
- 單元測試只覆蓋 `ClickLinkRewriter`（URL 改寫邏輯），**沒覆蓋實際寫入流程**

從 portfolio 截圖看，當時的 demo 數字（click_count = 187 / 95 / ...）是用 seed script 直接 INSERT 到 click_count 欄位，沒有真的走過 `resolveAndRecord` 流程。bug 因此一直藏到實機 E2E 才暴露。

→ 補上 `ClickEventWriterTest` 涵蓋三個 case（正常、欄位 truncate、save 例外），雖然單測無法檢驗 proxy 行為，但 catch 流程的 swallow 邏輯有保護網。**Proxy / @Async 真正驗證需要整合測試**，記錄於 `test-coverage-plan.md` 待補。

---

## 五、學到的事

1. **`@Async` + `@Transactional` 標在同一個方法、又是 self-invocation 呼叫的 → 兩個都失效**。Spring AOP 只攔截「跨 bean / 跨 proxy 的呼叫」。
2. **`@Modifying` JPQL 對 outer TX 的依賴比 `save()` 嚴格**。`save()` 內建短交易能 fallback，`@Modifying` 不行。debug 時看到「一半成功一半失敗」要警覺是 TX 邊界問題。
3. **`@Async` 指定 executor 前，要確認該池真的有空閒 thread**。被無限迴圈占住的池，外觀正常但對外是黑洞。第一時間想到 thread pool saturation 的指標：thread name 出現預期 prefix、log 出現預期輸出，**兩者都沒有**就要懷疑 task 卡在 queue。
4. **dev 期間 click_count 跟 click_events count 不一致** 是強訊號——正常情況兩者應該嚴格 1:1。對發現雙層 bug 提供了很好的「資料不一致」入口。
5. **單元測試覆蓋率高 ≠ 真的可靠**。`ClickLinkRewriter` 有 7 個 test 都綠燈，但實際寫入流程從來沒被 end-to-end 跑過。

---

## 六、commit

```
fix: 修點擊追蹤 click_count 不增加的雙層 bug

- 拆 ClickEventWriter 獨立 bean，解 self-invocation 繞 AOP proxy
- AsyncConfig 新增 clickEventExecutor 池（core=2, max=8, queue=500），
  避免共用 broadcastWorkerExecutor 被 worker 無限迴圈卡死
- 新增 ClickEventWriterTest 覆蓋寫入邏輯與例外吞掉路徑
```
