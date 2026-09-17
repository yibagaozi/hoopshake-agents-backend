package com.cnsportiot.cloud.harness.llm;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** LLM 调用统一收口 */
public interface LlmGateway {

    /** 是否已装配可用的 LLM */
    boolean isEnabled();

    /** 流式生成。回调发生在网关内部线程;返回句柄用于中断 */
    StreamHandle stream(StreamRequest request, StreamSink sink);

    /**
     * 一次性(非流式,阻塞)补全,用于意图分类 / query 改写 / 短建议等 FAST 档场景
     * 不可用或失败时返回 {@link Optional#empty()},调用方据此退回规则/兜底,不抛断链
     */
    Optional<String> complete(CompletionRequest request);

    /**
     * 熔断器状态码(0=CLOSED,1=HALF_OPEN,2=OPEN),供运维只读快照
     * 默认空(未启用 LLM 或未装熔断);仅 Spring AI 实现在开启熔断时返回
     */
    default OptionalInt circuitState() {
        return OptionalInt.empty();
    }


    /**
     * 带用量的一次性补全。默认实现委托 {@link #complete} 并按
     * {@link TokenEstimator} 估算用量(estimated=true),使未接入真实 usage 的实现也能被审计。
     */
    default Optional<CompletionResult> completeWithUsage(CompletionRequest request) {
        return complete(request).map(content -> new CompletionResult(
                content,
                Usage.estimate(request.system(), request.user(), content, null)));
    }

    /** 一次性补全的输入 */
    record CompletionRequest(String system, String user, Tier tier, Integer maxTokens) {}

    /** 一次性补全的产出 + 用量 */
    record CompletionResult(String content, Usage usage) {}

    /**
     * 一次调用的 token 用量。
     *
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param model            实际模型名(提供方回传优先),可空
     * @param estimated        true=提供方未回传 usage,本值由估算得来
     */
    record Usage(int promptTokens, int completionTokens, String model, boolean estimated) {

        public int totalTokens() {
            return promptTokens + completionTokens;
        }

        /** 按本项目既有口径估算(CJK≈1,其余≈1/4 字符)。 */
        public static Usage estimate(String system, String user, String output, String model) {
            return new Usage(
                    TokenEstimator.estimate(system) + TokenEstimator.estimate(user),
                    TokenEstimator.estimate(output),
                    model,
                    true);
        }
    }

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

        /**
         * 本轮用量。在 {@link #onComplete}/{@link #onError} <b>之前</b>回调一次(至多一次);
         * 提供方未回传 usage 时给估算值({@code estimated=true})。默认忽略。
         */
        default void onUsage(Usage usage) { }
    }

    /** 中断句柄 */
    interface StreamHandle {
        void cancel();
    }
}
