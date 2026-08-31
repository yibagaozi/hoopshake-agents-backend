package com.cnsportiot.edge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 线程池规划 */
@Configuration
public class AsyncConfig {

    private static final int CAMERAS = 4;

    @Bean
    @Primary
    public TaskExecutor captureIoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CAMERAS * 5);
        executor.setMaxPoolSize(CAMERAS * 8);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("capture-io-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /** 云端 REST 调用,与实时采集链路隔离,避免网络阻塞拖累录制 */
    @Bean
    public TaskExecutor cloudIoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("cloud-io-");
        executor.initialize();
        return executor;
    }

    /** WS 扇出 */
    @Bean
    public TaskExecutor wsBroadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("ws-broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }
}

