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

    /**
     * 课后算法批处理:单线程(单机单会话),墙钟可长达数十分钟,
     * 与采集(captureIo)/出云网络(cloudIo)线程池隔离,避免长阻塞拖累实时链路。
     */
    @Bean
    public TaskExecutor batchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("session-batch-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * 现场人脸采集:单线程(单机单采集机位,同一时刻只跑一个 enroll 进程),
     * 墙钟数十秒到数分钟,与采集/出云/批处理线程池隔离。
     */
    @Bean
    public TaskExecutor enrollExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("enroll-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
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

