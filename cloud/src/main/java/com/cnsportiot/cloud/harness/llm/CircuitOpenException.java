package com.cnsportiot.cloud.harness.llm;

/**
 * 熔断打开、快速失败时抛出
 * 上层据此走降级(对话端点 → 50310 / 兜底话术)
 */
public class CircuitOpenException extends RuntimeException {
    public CircuitOpenException() {
        super("LLM circuit open, failing fast");
    }
}
