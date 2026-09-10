package com.cnsportiot.cloud.harness.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 令牌桶:突发上限、匀速回填、按 key 隔离、null 放行、拒绝计数。 */
class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);

    private TokenBucketRateLimiter limiter(int burst, int perMinute) {
        return new TokenBucketRateLimiter(burst, perMinute, now::get);
    }

    @Test void allowsBurstThenDenies() {
        TokenBucketRateLimiter rl = limiter(3, 60);   // 桶容量 3,1 令牌/秒
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isFalse();     // 桶已空
        assertThat(rl.rejectedCount()).isEqualTo(1);
    }

    @Test void refillsOverTime() {
        TokenBucketRateLimiter rl = limiter(2, 60);   // 1 令牌/秒
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isFalse();
        now.addAndGet(1000);                          // 过 1 秒 → 回填 1
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isFalse();
    }

    @Test void refillCapsAtCapacity() {
        TokenBucketRateLimiter rl = limiter(2, 60);
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isTrue();
        now.addAndGet(60_000);                        // 长时间空闲:最多回满到容量 2
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isFalse();     // 不超容量
    }

    @Test void perKeyIsolation() {
        TokenBucketRateLimiter rl = limiter(1, 60);
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isFalse();
        assertThat(rl.tryAcquire("b")).isTrue();      // 另一账号有自己的桶
    }

    @Test void nullKeyAlwaysAllowed() {
        TokenBucketRateLimiter rl = limiter(1, 60);
        assertThat(rl.tryAcquire(null)).isTrue();
        assertThat(rl.tryAcquire(null)).isTrue();
        assertThat(rl.rejectedCount()).isZero();
    }
}
