package com.cnsportiot.cloud.harness.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 舱壁:并发上限、释放归还、在途/剩余计数、不限模式、拒绝计数。 */
class LlmStreamBulkheadTest {

    @Test void capsConcurrencyThenRejects() {
        LlmStreamBulkhead b = new LlmStreamBulkhead(2);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.active()).isEqualTo(2);
        assertThat(b.available()).isZero();
        assertThat(b.tryAcquire()).isFalse();          // 满载
        assertThat(b.rejectedCount()).isEqualTo(1);
    }

    @Test void releaseReturnsPermit() {
        LlmStreamBulkhead b = new LlmStreamBulkhead(1);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();
        b.release();
        assertThat(b.active()).isZero();
        assertThat(b.tryAcquire()).isTrue();           // 归还后可再取
    }

    @Test void unlimitedWhenNonPositive() {
        LlmStreamBulkhead b = new LlmStreamBulkhead(0);
        for (int i = 0; i < 1000; i++) {
            assertThat(b.tryAcquire()).isTrue();
        }
        assertThat(b.active()).isZero();               // 不限模式不跟踪占用
        assertThat(b.available()).isEqualTo(Integer.MAX_VALUE);
        assertThat(b.rejectedCount()).isZero();
    }

    @Test void maxPermitsReported() {
        assertThat(new LlmStreamBulkhead(5).maxPermits()).isEqualTo(5);
    }
}
