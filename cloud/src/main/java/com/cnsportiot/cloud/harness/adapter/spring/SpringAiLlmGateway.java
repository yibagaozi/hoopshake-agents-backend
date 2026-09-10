package com.cnsportiot.cloud.harness.adapter.spring;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.config.AgentProperties.ModelSpec;
import com.cnsportiot.cloud.harness.llm.Backoff;
import com.cnsportiot.cloud.harness.llm.CircuitBreaker;
import com.cnsportiot.cloud.harness.llm.CircuitOpenException;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.RetryFallback;
import com.cnsportiot.cloud.harness.llm.Sleeper;
import com.cnsportiot.cloud.harness.llm.TokenBudget;
import com.cnsportiot.cloud.harness.llm.TransientErrors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LlmGateway} 的 Spring AI 实现
 * 已含:档位映射、流式、工具(function-calling),Token 预算裁剪,降级链(瞬时错误同模型重试 跨模型 fallback)
 * 降级判定与预算逻辑收敛在框架中立的 (TokenBudget/RetryFallback/TransientErrors)
 */
@Service
@ConditionalOnProperty(prefix = "hoopshake.agent", name = "enabled", havingValue = "true")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;
    private final AgentProperties props;
    private final SpringAiToolCallbackFactory toolCallbackFactory;
    private final TokenBudget tokenBudget;

    private final Backoff backoff;
    private final CircuitBreaker breaker;   // null = 熔断关闭
    /** 流式降级重订阅的非阻塞延迟调度(退避,不占用 reactor 线程) */
    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-retry-backoff");
                t.setDaemon(true);
                return t;
            });

    public SpringAiLlmGateway(ChatModel chatModel, AgentProperties props,
                              SpringAiToolCallbackFactory toolCallbackFactory, TokenBudget tokenBudget) {
        this.chatClient = ChatClient.create(chatModel);
        this.props = props;
        this.toolCallbackFactory = toolCallbackFactory;
        this.tokenBudget = tokenBudget;
        var r = props.getResilience();
        this.backoff = new Backoff(r.getBackoff().getBaseMillis(), r.getBackoff().getMaxMillis(),
                r.getBackoff().isJitter());
        this.breaker = r.getCircuit().isEnabled()
                ? new CircuitBreaker(r.getCircuit().getFailureThreshold(), r.getCircuit().getOpenMillis())
                : null;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        retryScheduler.shutdownNow();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public StreamHandle stream(StreamRequest request, StreamSink sink) {
        List<Message> messages;
        try {
            // T13:在上下文预算内裁剪历史(丢最旧);system+user+预留超限抛 TOKEN_BUDGET_EXCEEDED
            List<Turn> trimmed = tokenBudget.fitHistory(
                    request.system(), request.user(), request.history(), request.maxTokens());
            messages = toMessages(trimmed);
        } catch (RuntimeException e) {
            sink.onError(e);
            return () -> { };
        }

        // 熔断:OPEN 冷却期内快速失败,不打后端
        if (breaker != null && !breaker.allow()) {
            sink.onError(new CircuitOpenException());
            return () -> { };
        }

        // T14:主档 → fallback,各自允许 maxRetries 次;流式仅在"首个 delta 之前的瞬时错误"重订阅
        List<ModelSpec> attempts = RetryFallback.expand(
                List.of(props.specForTier(request.tier()), props.getFallback()), props.getMaxRetries());
        StreamState state = new StreamState();
        subscribeAttempt(0, attempts, request, messages, sink, state);

        return () -> {
            state.cancelled.set(true);
            Disposable d = state.current.get();
            if (d != null) {
                try { d.dispose(); } catch (RuntimeException ignore) { }
            }
        };
    }

    private void subscribeAttempt(int idx, List<ModelSpec> attempts, StreamRequest request,
                                  List<Message> messages, StreamSink sink, StreamState state) {
        if (state.cancelled.get()) {
            return;
        }
        Disposable d;
        try {
            ChatClient.ChatClientRequestSpec req = chatClient.prompt()
                    .system(request.system() == null ? "" : request.system())
                    .messages(messages)
                    .user(request.user())
                    .options(buildOptions(attempts.get(idx), request.maxTokens()));

            if (request.tools() != null && !request.tools().isEmpty()) {
                // 工具事件也算"已产生副作用":一旦触发就不再降级重订阅,避免工具重复执行/重复审计
                List<ToolCallback> callbacks = toolCallbackFactory.build(
                        request.tools(), request.toolContext(),
                        (name, status, label) -> {
                            state.emitted.set(true);
                            sink.onToolEvent(name, status, label);
                        });
                req = req.toolCallbacks(callbacks);
            }

            d = req.stream().content().subscribe(
                    text -> { state.emitted.set(true); settleSuccess(state); sink.onDelta(text); },
                    err -> onAttemptError(err, idx, attempts, request, messages, sink, state),
                    () -> { settleSuccess(state); sink.onComplete("stop"); });
        } catch (RuntimeException e) {
            onAttemptError(e, idx, attempts, request, messages, sink, state);
            return;
        }
        state.current.set(d);
    }

    private void onAttemptError(Throwable err, int idx, List<ModelSpec> attempts, StreamRequest request,
                                List<Message> messages, StreamSink sink, StreamState state) {
        boolean canFallback = !state.cancelled.get()
                && !state.emitted.get()                 // 未产出任何 delta/工具事件才可切换
                && idx + 1 < attempts.size()
                && TransientErrors.isTransient(err);
        if (canFallback) {
            // 退避后再重订阅(非阻塞,交调度线程),防重试风暴
            long delay = backoff.nextDelayMillis(idx);
            if (delay > 0) {
                retryScheduler.schedule(
                        () -> subscribeAttempt(idx + 1, attempts, request, messages, sink, state),
                        delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                subscribeAttempt(idx + 1, attempts, request, messages, sink, state);
            }
        } else {
            settleFailure(state);   // 终态失败:计入熔断
            sink.onError(err);
        }
    }

    private void settleSuccess(StreamState state) {
        if (breaker != null && state.settled.compareAndSet(false, true)) {
            breaker.onSuccess();
        }
    }

    private void settleFailure(StreamState state) {
        if (breaker != null && state.settled.compareAndSet(false, true)) {
            breaker.onFailure();
        }
    }

    @Override
    public Optional<String> complete(CompletionRequest request) {
        if (breaker != null && !breaker.allow()) {
            return Optional.empty();   // 熔断打开:快速失败,调用方退回规则/兜底
        }
        try {
            String content = RetryFallback.execute(
                    List.of(props.specForTier(request.tier()), props.getFallback()),
                    props.getMaxRetries(), TransientErrors::isTransient,
                    spec -> chatClient.prompt()
                            .system(request.system() == null ? "" : request.system())
                            .user(request.user() == null ? "" : request.user())
                            .options(buildOptions(spec, request.maxTokens()))
                            .call()
                            .content(),
                    backoff, Sleeper.REAL);
            if (breaker != null) {
                breaker.onSuccess();
            }
            return Optional.ofNullable(content);
        } catch (RuntimeException e) {
            if (breaker != null) {
                breaker.onFailure();
            }
            return Optional.empty();   // 分类/短补全失败不断链,调用方退回规则
        }
    }

    private OpenAiChatOptions.Builder buildOptions(ModelSpec spec, Integer maxTokens) {
        OpenAiChatOptions.Builder ob = OpenAiChatOptions.builder();
        ob.model(spec.getModel());
        if (maxTokens != null) {
            ob.maxTokens(maxTokens);
        }
        // 推理档:high/max → reasoning_effort;off(关思考)需 GLM 扩展参数 reasoning.enabled=false,
        // 走 extra-body,待确认字段名后接入(见 §5.2 备注);当前 off 不显式设,退回模型默认。
        if (spec.reasoningEffort() != null) {
            ob.reasoningEffort(spec.reasoningEffort());
        }
        return ob;
    }

    private List<Message> toMessages(List<Turn> history) {
        List<Message> messages = new ArrayList<>();
        if (history != null) {
            for (Turn t : history) {
                messages.add("assistant".equalsIgnoreCase(t.role())
                        ? new AssistantMessage(t.content())
                        : new UserMessage(t.content()));
            }
        }
        return messages;
    }

    /** 一次流式请求(含降级重订阅)的可变状态 */
    private static final class StreamState {
        final AtomicBoolean emitted = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean settled = new AtomicBoolean(false);   // 熔断成功/失败只记一次
        final AtomicReference<Disposable> current = new AtomicReference<>();
    }
}
