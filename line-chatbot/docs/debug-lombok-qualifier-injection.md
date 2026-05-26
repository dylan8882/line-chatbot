# 除錯紀錄：Lombok `@RequiredArgsConstructor` + `@Qualifier` 注入失敗

> 發生於：2026-05-27（Phase 6 完成後嘗試啟動）
>
> 症狀：`./dev.sh` 啟動 backend 出現 `APPLICATION FAILED TO START`，找到 3 個 `Executor` 型別 bean 無法解析。

---

## 一、錯誤訊息

```
Description:
Parameter 2 of constructor in com.linechatbot.service.BroadcastWorkerManager
required a single bean, but 3 were found:
    - lineMessageExecutor: defined by method 'lineMessageExecutor' in AsyncConfig
    - broadcastWorkerExecutor: defined by method 'broadcastWorkerExecutor' in BroadcastConfig
    - taskScheduler: defined by method 'taskScheduler' in TaskSchedulingConfigurations

Action:
Consider marking one of the beans as @Primary, updating the consumer to accept multiple beans,
or using @Qualifier to identify the bean that should be consumed
```

---

## 二、Spring DI 的解析順序

當你在某個 service 寫：

```java
public BroadcastWorkerManager(Executor workerExecutor) { ... }
```

Spring 拿到 `Executor` 型別後依下列順序決定要注入哪個 bean：

| 步驟 | 規則 | 失敗時 |
|------|------|--------|
| 1 | 只有 1 個同型別 bean？ | 直接注入；否則進下一步 |
| 2 | 有人標 `@Primary`？ | 注入該 bean；否則進下一步 |
| 3 | 參數上有 `@Qualifier("xxx")`？ | 找名字叫 xxx 的 bean；否則進下一步 |
| 4 | 參數名稱剛好等於某 bean name？ | 用參數名匹配（需要 `-parameters` 編譯旗標） |
| 5 | 都不行 | 噴錯：「找到 N 個，不知道要哪個」 |

---

## 三、本專案 Executor bean 的演進

| Phase | 動作 | Executor 型別 bean 累積數 |
|-------|------|--------------------------|
| Phase 1 | `AsyncConfig` 定義 `lineMessageExecutor` | 1 |
| Phase 3 | `BroadcastConfig` 定義 `broadcastWorkerExecutor` | 2 |
| Phase 6 | `BroadcastConfig` 加 `@EnableScheduling` | **3** |

第三個 bean 從哪來？`@EnableScheduling` 觸發 Spring Boot 自動建立一個叫 `taskScheduler` 的 bean，型別是 `ThreadPoolTaskScheduler`，而 `TaskScheduler` 介面**繼承** `Executor`，所以也被列為 `Executor` 候選。

```
+ Phase 6: @EnableScheduling
                ↓
Spring Boot 自動建：
   taskScheduler (ThreadPoolTaskScheduler → implements TaskScheduler → extends Executor)
                ↓
Executor 候選池：
   ├── lineMessageExecutor
   ├── broadcastWorkerExecutor
   └── taskScheduler  ← 新冒出來的
```

---

## 四、為什麼之前 Phase 1-5 沒事？

Phase 3 加進 `broadcastWorkerExecutor` 時其實也有 2 個 Executor，理論上同樣需要 `@Qualifier`。當時沒爆炸是因為：

1. Spring Boot 某些自動配置在編譯有保留參數名稱（`-parameters`）時可走「參數名 = bean 名」匹配
2. 或某些版本對 `Executor` 型別有特殊 fallback

無論哪個原因，Phase 6 加進第 3 個 bean 後，**任何 fallback 都不夠用了**，必須明確 `@Qualifier`。

> **教訓**：DI 在「同型別 1 個」時鬆散好說，「2 個以上」就應該直接 `@Qualifier` 寫死，不要依賴 Spring 猜測。

---

## 五、原本程式碼為什麼明明寫了 `@Qualifier` 還失敗

`BroadcastWorkerManager.java` 原樣：

```java
@Component
@RequiredArgsConstructor                       // Lombok 自動生 constructor
public class BroadcastWorkerManager {

    private final BroadcastQueueService queueService;
    private final BroadcastChunkProcessor chunkProcessor;

    @Qualifier("broadcastWorkerExecutor")      // ← annotation 寫在「欄位」上
    private final Executor workerExecutor;

    // 沒手寫 constructor，靠 Lombok 生
}
```

### Lombok `@RequiredArgsConstructor` 的預設行為

**只把欄位「型別」和「名稱」帶到 constructor 參數，不會把欄位上的 annotation 複製過去。**

實際生成的 constructor 等於：

```java
public BroadcastWorkerManager(
    BroadcastQueueService queueService,
    BroadcastChunkProcessor chunkProcessor,
    Executor workerExecutor              // ← @Qualifier 不見了！
) {
    this.queueService = queueService;
    this.chunkProcessor = chunkProcessor;
    this.workerExecutor = workerExecutor;
}
```

於是 Spring DI 流程跑到：

```
Spring 看到 constructor 第 3 個參數
  → 找 Executor 型別 bean：找到 3 個
  → 看參數有沒有 @Qualifier：沒有（被 Lombok 吃掉了）
  → 看參數名是否對應某 bean：javac 沒帶 -parameters，名字變 arg2，沒匹配
  → 噴錯：APPLICATION FAILED TO START
```

---

## 六、修法

在 `backend/` 加一個 `lombok.config`：

```
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value
```

這個檔字面意思：**「Lombok 啊，看到 `@Qualifier`（或 `@Value`）這個 annotation 寫在欄位時，請把它複製到 `@RequiredArgsConstructor` 生成的 constructor 參數上」**。

重新編譯後，Lombok 產出的 constructor 變成：

```java
public BroadcastWorkerManager(
    BroadcastQueueService queueService,
    BroadcastChunkProcessor chunkProcessor,
    @Qualifier("broadcastWorkerExecutor") Executor workerExecutor   // ← 帶上來了
) { ... }
```

Spring 看到 `@Qualifier`，直接挑名字符合的 bean 注入，零猜測。

---

## 七、為什麼順便加 `@Value`

`@Value("${some.config}")` 寫在欄位上時也踩同樣坑。例如：

```java
@RequiredArgsConstructor
public class SomeService {
    @Value("${app.timeout-ms:5000}")
    private final long timeoutMs;        // ← annotation 也會被 Lombok 吃掉
}
```

Lombok 生成的 constructor 不會帶 `@Value`，Spring 不知道要從哪裡注入這個 long → 啟動失敗。

我們專案目前所有 `@Value` 都用在**非 final** 欄位上，走 field injection（Spring 直接寫欄位），不經 constructor，所以還沒踩雷。但先在 `lombok.config` 一起設好，未來改用 final / 改用 constructor injection 時不會回頭來補。

---

## 八、其他可行的修法（為什麼沒選）

| 方案 | 細節 | 為什麼沒選 |
|------|------|----------|
| **手寫 constructor** | 移除 `@RequiredArgsConstructor`，手動寫 constructor 並把 `@Qualifier` 加到參數 | 只解該檔；其他類別未來踩同樣坑要重複處理 |
| **`@Resource(name="...")`** | JSR-250 標準 annotation，作用在欄位上、不需走 Lombok constructor | 等於放棄 constructor injection（field injection 是反模式） |
| **加 `-parameters` 編譯旗標** | 讓參數名稱被保留，Spring 走 step 4「參數名 = bean 名」匹配 | 要求欄位名必須跟 bean 名一致；當 bean 名長就難用；且依賴 javac 設定，比 `lombok.config` 更隱晦 |
| **`@Primary`** | 在 `broadcastWorkerExecutor` 上加 `@Primary` 把它變成預設 | 只解一個 case；如果 `lineMessageExecutor` 也想要明確指定就沒法 |
| **`lombok.config`**（本案採用） | 一次設定 Project 範圍生效；新類別自動享受 | ✅ 範圍廣、不侵入個別檔、業界常見 Spring + Lombok 標準配置 |

---

## 九、視覺化前後對照

```
                  修之前                              修之後
                  ┌──────────┐                       ┌──────────┐
程式碼欄位          │@Qualifier│                       │@Qualifier│
                  │  Executor│                       │  Executor│
                  └─────┬────┘                       └─────┬────┘
                        │ Lombok                            │ Lombok（讀 lombok.config）
                        ▼                                   ▼
Lombok 生成的       ┌─────────┐                       ┌──────────────────────┐
constructor 參數    │ Executor│（沒 @Qualifier）       │ @Qualifier Executor │
                  └─────┬────┘                       └──────────┬───────────┘
                        │ Spring                                  │ Spring
                        ▼                                         ▼
                  ❌ 找到 3 個 Executor                ✅ 精準挑 broadcastWorkerExecutor
                     不知道挑哪個                         注入成功
```

---

## 十、未來預防

1. **DI 同型別 ≥ 2 個就明確 `@Qualifier`** — 不要依賴 Spring 的隱式 fallback
2. **新 Spring + Lombok 專案開頭就放 `lombok.config`** — 不只 `@Qualifier`、`@Value` 等所有「Spring 注入用」annotation 都該被複製
3. **新增 `@EnableScheduling` / `@EnableAsync` 等「會自動建 bean」的 annotation 時** — 用 `Ctrl+F` 搜尋一下專案有沒有依賴隱式 fallback 的注入點

---

## 十一、參考

- [Lombok 官方 — onConstructor / copyableAnnotations](https://projectlombok.org/features/constructor)
- [Spring Framework Reference — Autowiring fine-tuning](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired-qualifiers.html)
- [Stack Overflow — Lombok @Qualifier not copied](https://stackoverflow.com/questions/41700434/lombok-and-qualifier-on-final-field)
