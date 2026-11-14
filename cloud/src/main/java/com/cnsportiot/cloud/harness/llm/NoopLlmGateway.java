package com.cnsportiot.cloud.harness.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * agent 关闭时的兜底网关
 * 保证 app 在无 GLM key 时照常启动,对话端点降级返回 LLM_UNAVAILABLE。
 */
@Component
@ConditionalOnProperty(prefix = "hoopshake.agent", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopLlmGateway implements LlmGateway {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public StreamHandle stream(StreamRequest request, StreamSink sink) {
        sink.onError(new IllegalStateException("LLM 未启用(hoopshake.agent.enabled=false)"));
        return () -> { };
    }

    @Override
    public Optional<String> complete(CompletionRequest request) {
        return Optional.empty();   // 未启用:调用方退回规则/兜底
    }
}

