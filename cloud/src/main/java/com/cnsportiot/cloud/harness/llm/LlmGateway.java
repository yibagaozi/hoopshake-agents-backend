package com.cnsportiot.cloud.harness.llm;

import java.util.List;

/** LLM 调用统一收口 */
public interface LlmGateway {

    /** 是否已装配可用的 LLM */
    boolean isEnabled();

    /** 流式生成。回调发生在网关内部线程;返回句柄用于中断 */
    StreamHandle stream(StreamRequest request, StreamSink sink);

    /** 一次对话轮次的输入。history 为最近若干轮上下文 */
    record StreamRequest(
            String system,
            List<Turn> history,
            String user,
            Tier tier,
            Integer maxTokens) {}

    /** 历史轮次 */
    record Turn(String role, String content) {}

    /** 流式事件下游 */
    interface StreamSink {
        void onDelta(String text);
        void onComplete(String finishReason);
        void onError(Throwable error);
    }

    /** 中断句柄 */
    interface StreamHandle {
        void cancel();
    }
}
