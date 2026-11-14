package com.cnsportiot.cloud.harness.llm;

import com.cnsportiot.cloud.config.AgentProperties.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 退避在重试之间生效,最后一次尝试后不再等待;非瞬时立即上抛不等待。 */
class RetryFallbackBackoffTest {

    private static final ModelSpec A = new ModelSpec("A", ModelSpec.Reasoning.OFF);
    private static final ModelSpec B = new ModelSpec("B", ModelSpec.Reasoning.OFF);

    @Test void backoffBetweenAttempts_notAfterLast() {
        List<Long> sleeps = new ArrayList<>();
        Sleeper capture = sleeps::add;
        Backoff backoff = new Backoff(10, 1000, false);   // 无抖动:10,20,40,...

        assertThatThrownBy(() -> RetryFallback.execute(
                List.of(A), 2, t -> true,           // 单档 3 次尝试,全瞬时
                spec -> { throw new RuntimeException("timeout"); },
                backoff, capture))
                .isInstanceOf(RuntimeException.class);

        // 3 次尝试 → 2 次退避(尝试1后、尝试2后;尝试3后不等)
        assertThat(sleeps).containsExactly(10L, 20L);
    }

    @Test void backoffGrowsAcrossChain() {
        List<Long> sleeps = new ArrayList<>();
        Backoff backoff = new Backoff(10, 1000, false);
        assertThatThrownBy(() -> RetryFallback.execute(
                List.of(A, B), 1, t -> true,        // 两档各 2 次 = 4 尝试 → 3 退避
                spec -> { throw new RuntimeException("503 unavailable"); },
                backoff, sleeps::add))
                .isInstanceOf(RuntimeException.class);
        assertThat(sleeps).containsExactly(10L, 20L, 40L);
    }

    @Test void nonTransient_noSleepImmediateThrow() {
        List<Long> sleeps = new ArrayList<>();
        assertThatThrownBy(() -> RetryFallback.execute(
                List.of(A, B), 2, TransientErrors::isTransient,
                spec -> { throw new RuntimeException("400 bad request"); },
                new Backoff(10, 1000, false), sleeps::add))
                .isInstanceOf(RuntimeException.class);
        assertThat(sleeps).isEmpty();
    }
}
