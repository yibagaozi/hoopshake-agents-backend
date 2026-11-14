package com.cnsportiot.cloud.harness.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 熔断状态机:CLOSED → (连续失败) OPEN → (冷却) HALF_OPEN → 成功 CLOSED / 失败 OPEN。 */
class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private CircuitBreaker breaker(int threshold, long openMs) {
        return new CircuitBreaker(threshold, openMs, now::get);
    }

    @Test void opensAfterThreshold_failsFastDuringCooldown() {
        CircuitBreaker cb = breaker(3, 1000);
        assertThat(cb.allow()).isTrue();
        cb.onFailure(); cb.onFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        cb.onFailure();   // 第 3 次 → OPEN
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allow()).isFalse();               // 冷却期内快速失败
        now.addAndGet(500);
        assertThat(cb.allow()).isFalse();               // 仍在冷却
    }

    @Test void halfOpenTrial_successCloses() {
        CircuitBreaker cb = breaker(2, 1000);
        cb.onFailure(); cb.onFailure();                 // OPEN
        now.addAndGet(1000);                            // 冷却到期
        assertThat(cb.allow()).isTrue();                // 放一次试探(HALF_OPEN)
        assertThat(cb.allow()).isFalse();               // 半开只允许一个在途试探
        cb.onSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allow()).isTrue();
    }

    @Test void halfOpenTrial_failureReopens() {
        CircuitBreaker cb = breaker(2, 1000);
        cb.onFailure(); cb.onFailure();                 // OPEN
        now.addAndGet(1000);
        assertThat(cb.allow()).isTrue();                // HALF_OPEN 试探
        cb.onFailure();                                 // 试探失败 → 重新 OPEN
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allow()).isFalse();
    }

    @Test void successResetsFailureStreak() {
        CircuitBreaker cb = breaker(3, 1000);
        cb.onFailure(); cb.onFailure();
        cb.onSuccess();                                 // 清零
        cb.onFailure(); cb.onFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);   // 未达阈值
    }
}
