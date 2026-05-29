package com.linechatbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;

/**
 * 非同步處理設定。本專案有三個獨立的執行緒池：
 *
 * <ul>
 *   <li>{@code lineMessageExecutor}：LINE Webhook 訊息處理（QA / AI 回覆）</li>
 *   <li>{@code broadcastWorkerExecutor}（在 BroadcastConfig）：推播 worker 無限迴圈（4 個 thread 永久佔用）</li>
 *   <li>{@code clickEventExecutor}：點擊事件寫入（不能用 broadcastWorker，因為它的 core threads 全被 worker loop 占住）</li>
 * </ul>
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * LINE 訊息處理執行緒池（QA / AI 回覆）
     * 核心執行緒：10，最大執行緒：50，佇列大小：1000
     */
    @Bean(name = "lineMessageExecutor")
    public Executor lineMessageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("line-msg-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("LINE 訊息處理執行緒池已初始化：core={}, max={}, queue={}", 10, 50, 1000);
        return executor;
    }

    /**
     * 點擊事件寫入執行緒池。
     *
     * <p>點擊事件為輕量 DB 寫入（一個 INSERT + 一個 UPDATE），單筆 sub-ms 等級，但爆發流量可能高
     * （大型推播後 5 分鐘內可能有上千次點擊）。獨立執行緒池避免：
     * <ol>
     *   <li>共用 lineMessageExecutor 時，點擊爆量會延遲 LINE Webhook 回覆</li>
     *   <li>共用 broadcastWorkerExecutor 時，被 worker 無限迴圈卡死無法執行</li>
     * </ol>
     */
    @Bean(name = "clickEventExecutor")
    public Executor clickEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("click-evt-");
        // 佇列滿了寧可同步寫（拖慢 redirect 也比丟失事件好）
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("點擊事件執行緒池已初始化：core={}, max={}, queue={}", 2, 8, 500);
        return executor;
    }
}
