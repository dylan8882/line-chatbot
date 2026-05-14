package com.linechatbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;

/**
 * 非同步處理設定，用於高併發 LINE Webhook 處理
 * 核心執行緒：10，最大執行緒：50，佇列大小：1000
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * LINE 訊息處理執行緒池
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
}
