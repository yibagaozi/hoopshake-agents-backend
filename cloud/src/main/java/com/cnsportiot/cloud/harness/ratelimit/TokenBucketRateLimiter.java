package com.cnsportiot.cloud.harness.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 按 key(账号)令牌桶限流(见 docs/agent/production-readiness.md §3)。对话是最贵端点,
 * 必须限:桶容量 {@code capacity}(允许突发),按 {@code refillPerMinute} 匀速回填。
 *
 * <p>纯逻辑、进程内、无外部依赖:单后端实例部署足够(多实例再换 Redis 计数)。
 * {@code clock} 可注入以便确定性单测。线程安全(每桶 synchronized;桶表用 ConcurrentHashMap)。
 * 对空闲且满的桶做惰性清理,避免 key 膨胀。
 */
public final class TokenBucketRateLimiter {

    /** 桶数量上限:超过即触发一次惰性清扫(移除满且空闲的桶)。 */
    private static final int MAX_BUCKETS = 100_000;

    private final double capacity;
    private final double refillPerMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Object, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();

    public TokenBucketRateLimiter(int capacity, int refillPerMinute) {
        this(capacity, refillPerMinute, System::currentTimeMillis);
    }

    public TokenBucketRateLimiter(int capacity, int refillPerMinute, LongSupplier clock) {
        this.capacity = Math.max(1, capacity);
        this.refillPerMillis = Math.max(1, refillPerMinute) / 60_000.0;
        this.clock = clock;
    }

    /** 消耗一个令牌;桶空则拒绝(记一次拒绝)。 */
    public boolean tryAcquire(Object key) {
        if (key == null) {
            return true;   // 无身份不在此拦截(鉴权闸另有其职)
        }
        if (buckets.size() > MAX_BUCKETS) {
            sweepIdleFull();
        }
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, clock.getAsLong()));
        boolean ok = b.tryConsume(clock.getAsLong(), refillPerMillis, capacity);
        if (!ok) {
            rejected.incrementAndGet();
        }
        return ok;
    }

    /** 拒绝累计数(供指标 gauge)。 */
    public long rejectedCount() {
        return rejected.get();
    }

    /** 当前跟踪的桶数(供观测)。 */
    public int trackedKeys() {
        return buckets.size();
    }

    private void sweepIdleFull() {
        long now = clock.getAsLong();
        buckets.forEach((k, b) -> {
            if (b.isFullAndIdle(now, refillPerMillis, capacity)) {
                buckets.remove(k, b);
            }
        });
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        Bucket(double tokens, long nowMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = nowMillis;
        }

        synchronized boolean tryConsume(long now, double refillPerMillis, double capacity) {
            refill(now, refillPerMillis, capacity);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean isFullAndIdle(long now, double refillPerMillis, double capacity) {
            refill(now, refillPerMillis, capacity);
            return tokens >= capacity;   // 已回满 → 无状态可丢,可安全移除
        }

        private void refill(long now, double refillPerMillis, double capacity) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerMillis);
                lastRefillMillis = now;
            }
        }
    }
}
