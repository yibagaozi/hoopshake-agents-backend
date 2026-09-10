package com.cnsportiot.cloud.harness.llm;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 指数退避 + 全抖动。 */
class BackoffTest {

    @Test void exponentialNoJitter() {
        Backoff b = new Backoff(500, 8000, false);
        assertThat(b.nextDelayMillis(0)).isEqualTo(500);
        assertThat(b.nextDelayMillis(1)).isEqualTo(1000);
        assertThat(b.nextDelayMillis(2)).isEqualTo(2000);
        assertThat(b.nextDelayMillis(3)).isEqualTo(4000);
        assertThat(b.nextDelayMillis(4)).isEqualTo(8000);
        assertThat(b.nextDelayMillis(5)).isEqualTo(8000);   // 触顶
        assertThat(b.nextDelayMillis(40)).isEqualTo(8000);  // 防溢出
    }

    @Test void fullJitterWithinCap() {
        Backoff b = new Backoff(500, 8000, true, new Random(42));
        for (int i = 0; i < 8; i++) {
            long cap = Math.min(500L << Math.min(i, 40), 8000L);
            long d = b.nextDelayMillis(i);
            assertThat(d).isBetween(0L, cap);
        }
    }

    @Test void noneIsZero() {
        assertThat(Backoff.none().nextDelayMillis(0)).isZero();
        assertThat(Backoff.none().nextDelayMillis(5)).isZero();
    }
}
