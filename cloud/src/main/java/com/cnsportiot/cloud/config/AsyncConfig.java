package com.cnsportiot.cloud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 有界、按用途命名的线程池
 * 知识灌库(embedding 是慢 IO)走独立小池,不与请求线程/其他异步任务抢占
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 知识导入/灌库:并发小、队列有界,避免长时间任务打爆 embedding 配额 */
    @Bean("knowledgeImportExecutor")
    public Executor knowledgeImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("knowledge-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
