package com.cnsportiot.cloud.harness.llm;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** 错误分级:瞬时可重试 vs 显式不可重试(黑名单一票否决) */
class TransientErrorsTest {

    private static RuntimeException err(String msg) {
        return new RuntimeException(msg);
    }

    // 瞬时:应重试
    @Test void timeoutIsTransient() {
        assertThat(TransientErrors.isTransient(new SocketTimeoutException("Read timed out"))).isTrue();
    }
    @Test void rateLimitIsTransient() {
        assertThat(TransientErrors.isTransient(err("HTTP 429 Too Many Requests"))).isTrue();
    }
    @Test void serverErrorIsTransient() {
        assertThat(TransientErrors.isTransient(err("upstream returned 503 Service Unavailable"))).isTrue();
        assertThat(TransientErrors.isTransient(err("model overloaded, please retry"))).isTrue();
    }
    @Test void connectionResetIsTransient() {
        assertThat(TransientErrors.isTransient(err("Connection reset by peer"))).isTrue();
    }
    @Test void transientWrappedInCauseChain() {
        RuntimeException outer = new RuntimeException("LLM call failed", new SocketTimeoutException("timed out"));
        assertThat(TransientErrors.isTransient(outer)).isTrue();
    }

    // 显式不可重试:不应重试(即便措辞含糊)
    @Test void contextLengthNotRetryable() {
        assertThat(TransientErrors.isTransient(err("This model's maximum context length is 128000 tokens"))).isFalse();
    }
    @Test void authNotRetryable() {
        assertThat(TransientErrors.isTransient(err("401 Unauthorized: invalid api key"))).isFalse();
    }
    @Test void badRequestNotRetryable() {
        assertThat(TransientErrors.isTransient(err("400 Bad Request: invalid request body"))).isFalse();
    }
    @Test void contentPolicyNotRetryable() {
        assertThat(TransientErrors.isTransient(err("response blocked by content filter / safety policy"))).isFalse();
    }

    // 黑名单一票否决:含糊带 transient 词也不重试
    @Test void nonRetryableVetoesEvenIfMentionsServer() {
        assertThat(TransientErrors.isTransient(err("context length exceeded on server"))).isFalse();
    }

    // 无信号:默认不重试
    @Test void unknownNotRetryable() {
        assertThat(TransientErrors.isTransient(err("something odd happened"))).isFalse();
    }
}
