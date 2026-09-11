package com.cnsportiot.cloud.harness.llm;

import com.cnsportiot.cloud.config.AgentProperties.ModelSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 降级链执行器:按 {@code chain} 顺序尝试每个模型规格,每个规格最多 {@code 1 + maxRetries} 次;
 * 瞬时错误重试/降级,非瞬时错误立即上抛;全部失败抛最后一个异常
 */
public final class RetryFallback {

    private RetryFallback() {}

    /** 无退避(即时重试),兼容旧调用 */
    public static <T> T execute(List<ModelSpec> chain, int maxRetries,
                                Predicate<Throwable> isTransient, Function<ModelSpec, T> attempt) {
        return execute(chain, maxRetries, isTransient, attempt, Backoff.none(), Sleeper.REAL);
    }

    /**
     * 带**指数退避+抖动**的降级链:瞬时错误在下一次尝试前按 {@code backoff} 等待,防重试风暴;
     * 最后一次尝试后不再等待。非瞬时错误立即上抛
     */
    public static <T> T execute(List<ModelSpec> chain, int maxRetries, Predicate<Throwable> isTransient,
                                Function<ModelSpec, T> attempt, Backoff backoff, Sleeper sleeper) {
        RuntimeException last = null;
        int retries = Math.max(0, maxRetries);
        int total = chain.size() * (retries + 1);
        int step = 0;                         // 全局尝试序号(用于退避递增)
        for (ModelSpec spec : chain) {
            for (int i = 0; i <= retries; i++) {
                try {
                    return attempt.apply(spec);
                } catch (RuntimeException e) {
                    last = e;
                    if (!isTransient.test(e)) {
                        throw e;               // 非瞬时:不重试、不降级
                    }
                    step++;
                    if (step < total) {        // 非最后一次尝试 → 退避等待
                        sleeper.sleepMillis(backoff.nextDelayMillis(step - 1));
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("降级链为空");
    }

    /** 把 [primary, fallback] 展开成扁平尝试序列(供流式逐个 spec 重订阅用) */
    public static List<ModelSpec> expand(List<ModelSpec> chain, int maxRetries) {
        List<ModelSpec> out = new ArrayList<>();
        for (ModelSpec spec : chain) {
            for (int i = 0; i <= Math.max(0, maxRetries); i++) {
                out.add(spec);
            }
        }
        return out;
    }
}

