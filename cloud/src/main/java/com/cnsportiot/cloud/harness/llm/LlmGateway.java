package com.cnsportiot.cloud.harness.llm;

import java.util.List;

/**
 * LLM 调用统一收口(档位映射 / 流式;预算、缓存、脱敏、降级为后续里程碑)。
 *
 * <p>业务层只认本接口,不接触 Spring AI / Reactor 类型 —— 唯一实现
 * {@code adapter/spring/SpringAiLlmGateway} 是允许 import Spring AI 的地方
 * (见 §0 不变量一、§1 分层)。
 */
public interface LlmGateway {

    /** 是否已装配可用的 LLM(总开关 hoopshake.agent.enabled)。 */
    boolean isEnabled();

    /**
     * 流式生成。回调发生在网关内部线程;返回句柄用于中断(§5.4)。
     */
    StreamHandle stream(StreamRequest request, StreamSink sink);

    /** 一次对话轮次的输入。history 为最近若干轮上下文(不含本轮 user)。 */
    record StreamRequest(
            String system,
            List<Turn> history,
            String user,
            Tier tier,
            Integer maxTokens) {}

    /** 历史轮次。role ∈ {"user","assistant"}。 */
    record Turn(String role, String content) {}

    /** 流式事件下游(纯回调,不暴露 Reactor)。 */
    interface StreamSink {
        void onDelta(String text);
        void onComplete(String finishReason);
        void onError(Throwable error);
    }

    /** 中断句柄。 */
    interface StreamHandle {
        void cancel();
    }
}
