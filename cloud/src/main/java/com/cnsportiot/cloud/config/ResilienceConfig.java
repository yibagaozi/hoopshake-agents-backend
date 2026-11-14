package com.cnsportiot.cloud.config;

import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 生产韧性组件装配, 全局 LLM 流并发舱壁 + 按账号 /ask 令牌桶限流,并把二者的在途/拒绝数注册为 Micrometer 指标 */
@Configuration
public class ResilienceConfig {

    /** 学生端 + 教师端共享的 LLM 流并发舱壁 */
    @Bean
    public LlmStreamBulkhead llmStreamBulkhead(AgentProperties props) {
        return new LlmStreamBulkhead(props.getResilience().getBulkhead().getMaxConcurrentStreams());
    }

    /** 按账号对 /ask 限流的令牌桶 */
    @Bean
    public TokenBucketRateLimiter askRateLimiter(AgentProperties props) {
        var rl = props.getResilience().getRateLimit();
        return new TokenBucketRateLimiter(rl.getBurst(), rl.getRefillPerMinute());
    }

    /**
     * 注册韧性指标(有 MeterRegistry 才注册,缺则静默跳过)。
     * 熔断状态 gauge 在 {@code SpringAiLlmGateway} 内注册(仅 agent.enabled=true 时有该 bean)。
     */
    @Bean
    public ResilienceMetricsBinder resilienceMetricsBinder(ObjectProvider<MeterRegistry> registry,
                                                           LlmStreamBulkhead bulkhead,
                                                           TokenBucketRateLimiter rateLimiter) {
        MeterRegistry reg = registry.getIfAvailable();
        if (reg != null) {
            Gauge.builder("hoopshake.llm.stream.active", bulkhead, LlmStreamBulkhead::active)
                    .description("in-flight LLM chat streams (bulkhead occupancy)").register(reg);
            Gauge.builder("hoopshake.llm.stream.available", bulkhead, LlmStreamBulkhead::available)
                    .description("free bulkhead permits").register(reg);
            Gauge.builder("hoopshake.llm.stream.rejected", bulkhead, b -> (double) b.rejectedCount())
                    .description("stream starts rejected by bulkhead (saturation)").register(reg);
            Gauge.builder("hoopshake.ask.ratelimit.rejected", rateLimiter, r -> (double) r.rejectedCount())
                    .description("/ask requests rejected by per-account rate limiter").register(reg);
            Gauge.builder("hoopshake.ask.ratelimit.keys", rateLimiter, TokenBucketRateLimiter::trackedKeys)
                    .description("accounts currently tracked by the rate limiter").register(reg);
        }
        return new ResilienceMetricsBinder();
    }

    /** 占位 bean:仅承载注册副作用,便于 Spring 生命周期管理。 */
    public static final class ResilienceMetricsBinder { }
}
