package com.linechatbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 推播 Worker 執行緒池
 * 與 LINE webhook 池隔離，避免推播大量任務塞滿後影響即時回覆。
 */
@Configuration
@EnableScheduling
@Slf4j
public class BroadcastConfig {

    /** Multicast chunk 大小 = LINE API 一次最多 500 人/批的硬上限。 */
    public static final int CHUNK_SIZE = 500;

    /** Push 模式 chunk 大小邊界：太小會 row 爆炸、太大會 worker 鎖太久。 */
    public static final int PUSH_CHUNK_MIN = 50;
    public static final int PUSH_CHUNK_MAX = 5000;

    /** Push 模式目標 chunk 處理時間（秒）：worker 鎖 chunk 的目標 wall time。 */
    public static final int PUSH_CHUNK_TARGET_SECONDS = 2;

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
