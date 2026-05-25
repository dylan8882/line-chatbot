package com.linechatbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 推播 Worker 執行緒池
 * 與 LINE webhook 池隔離，避免推播大量任務塞滿後影響即時回覆。
 */
@Configuration
@Slf4j
public class BroadcastConfig {

    /** 單一 chunk 的最大收件人數（LINE multicast 上限 500） */
    public static final int CHUNK_SIZE = 500;

    @Bean(name = "broadcastWorkerExecutor")
    public Executor broadcastWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("broadcast-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        log.info("推播 Worker 執行緒池已初始化：core=4, max=16, queue=100");
        return executor;
    }
}
