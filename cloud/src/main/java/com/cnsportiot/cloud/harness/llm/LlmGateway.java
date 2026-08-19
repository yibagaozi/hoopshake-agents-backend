package com.cnsportiot.cloud.harness.llm;

import com.cnsportiot.cloud.harness.tool.port.AgentTool;
import com.cnsportiot.cloud.harness.tool.port.ToolContext;

import java.util.List;

/** LLM 调用统一收口 */
public interface LlmGateway {

    /** 是否已装配可用的 LLM */
    boolean isEnabled();

    /** 流式生成。回调发生在网关内部线程;返回句柄用于中断 */
    StreamHandle stream(StreamRequest request, StreamSink sink);

    /**
     * 一次对话轮次的输入。history 为最近若干轮上下文
     *
     * @param tools       本轮可供模型调用的工具(空则纯对话);function-calling 的 ReAct 循环由底层框架内部完成
     * @param toolContext 工具调用的权威上下文(studentId 等),透传给 Hook / 审计】
     */
    record StreamRequest(
            String system,
            List<Turn> history,
            String user,
            Tier tier,
            Integer maxTokens,
            List<AgentTool> tools,
            ToolContext toolContext) {

        /** 纯对话(无工具)的便捷构造 */
        public StreamRequest(String system, List<Turn> history, String user, Tier tier, Integer maxTokens) {
            this(system, history, user, tier, maxTokens, List.of(), null);
        }
    }

    /** 历史轮次 */
    record Turn(String role, String content) {}

    /** 流式事件下游 */
    interface StreamSink {
        void onDelta(String text);
        void onComplete(String finishReason);
        void onError(Throwable error);

        /** 工具调用轨迹 */
        default void onToolEvent(String toolName, String status, String label) { }
    }

    /** 中断句柄 */
    interface StreamHandle {
        void cancel();
    }
}
