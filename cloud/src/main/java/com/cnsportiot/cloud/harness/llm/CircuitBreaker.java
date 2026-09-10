package com.cnsportiot.cloud.harness.llm;

import java.util.function.LongSupplier;

/**
 * 依赖熔断器:连续失败达阈值即 OPEN,
 * 冷却期内快速失败——不再打后端,避免对已宕/过载的 LLM 提供方持续施压
 * 冷却结束进 HALF_OPEN,放一次试探:成功CLOSED,失败重新 OPEN
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openUntil = 0L;
    private boolean halfOpenTrialInFlight = false;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this(failureThreshold, openMillis, System::currentTimeMillis);
    }

    public CircuitBreaker(int failureThreshold, long openMillis, LongSupplier clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(0, openMillis);
        this.clock = clock;
    }

    /** 是否放行本次调用。OPEN 且冷却未到 → false(快速失败) */
    public synchronized boolean allow() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.getAsLong() >= openUntil) {
                    state = State.HALF_OPEN;
                    halfOpenTrialInFlight = true;   // 放一次试探
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                if (!halfOpenTrialInFlight) {       // 半开态只允许一次在途试探
                    halfOpenTrialInFlight = true;
                    return true;
                }
                return false;
        }
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        halfOpenTrialInFlight = false;
    }

    public synchronized void onFailure() {
        halfOpenTrialInFlight = false;
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openUntil = clock.getAsLong() + openMillis;
    }

    public synchronized State state() {
        return state;
    }

    /** 数值化状态供指标 gauge:0=CLOSED,1=HALF_OPEN,2=OPEN */
    public synchronized int stateCode() {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }
}
