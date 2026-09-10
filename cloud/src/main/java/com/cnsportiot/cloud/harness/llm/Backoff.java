package com.cnsportiot.cloud.harness.llm;

import java.util.random.RandomGenerator;

/**
 * 指数退避 + 全抖动(full jitter),防重试风暴
 * cap   = min(baseMillis · 2^retryIndex, maxMillis)
 * delay = jitter ? U(0, cap) : cap
 * 无抖动时所有并发请求会在同一时刻重试,叠加成新的洪峰;全抖动把它摊平到 [0,cap]
 */
public final class Backoff {

    private final long baseMillis;
    private final long maxMillis;
    private final boolean jitter;
    private final RandomGenerator rng;

    public Backoff(long baseMillis, long maxMillis, boolean jitter) {
        this(baseMillis, maxMillis, jitter, new java.util.Random());
    }

    public Backoff(long baseMillis, long maxMillis, boolean jitter, RandomGenerator rng) {
        this.baseMillis = Math.max(0, baseMillis);
        this.maxMillis = Math.max(this.baseMillis, maxMillis);
        this.jitter = jitter;
        this.rng = rng;
    }

    /** 第 {@code retryIndex}(0 起)次重试前的等待毫秒 */
    public long nextDelayMillis(int retryIndex) {
        long cap = cap(Math.max(0, retryIndex));
        if (!jitter || cap <= 0) {
            return cap;
        }
        return rng.nextLong(cap + 1);   // [0, cap]
    }

    private long cap(int retryIndex) {
        if (retryIndex >= 62) {   // 防 1L<<n 溢出
            return maxMillis;
        }
        long grown = baseMillis << retryIndex;                // baseMillis · 2^retryIndex
        if (grown < baseMillis || grown > maxMillis) {        // 溢出或超上限
            return maxMillis;
        }
        return grown;
    }

    /** 关闭退避(delay 恒 0),用于兼容旧调用/测试 */
    public static Backoff none() {
        return new Backoff(0, 0, false);
    }
}
