package com.cnsportiot.cloud.harness.adapter.spring;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LlmGateway} 的 Spring AI 实现 —— <b>唯一允许 import Spring AI / Reactor 的地方</b>
 * (见 §0 不变量一、§1 ArchUnit)。仅当 {@code hoopshake.agent.enabled=true} 时装配。
 *
 * <p>本里程碑只做档位映射 + 流式;预算、语义缓存、脱敏、降级链为后续里程碑(§5.2)。
 */
@Service
@ConditionalOnProperty(prefix = "hoopshake.agent", name = "enabled", havingValue = "true")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;
    private final AgentProperties props;

    public SpringAiLlmGateway(ChatModel chatModel, AgentProperties props) {
        this.chatClient = ChatClient.create(chatModel);
        this.props = props;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public StreamHandle stream(StreamRequest request, StreamSink sink) {
        try {
            List<Message> history = new ArrayList<>();
            if (request.history() != null) {
                for (Turn t : request.history()) {
                    history.add("assistant".equalsIgnoreCase(t.role())
                            ? new AssistantMessage(t.content())
                            : new UserMessage(t.content()));
                }
            }

            ChatOptions.Builder<?> ob = ChatOptions.builder().model(modelFor(request.tier()));
            if (request.maxTokens() != null) {
                ob.maxTokens(request.maxTokens());
            }

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(request.system() == null ? "" : request.system())
                    .messages(history)
                    .user(request.user())
                    .options(ob.build());

            Disposable disposable = spec.stream().content()
                    .subscribe(sink::onDelta,
                            sink::onError,
                            () -> sink.onComplete("stop"));
            return disposable::dispose;
        } catch (RuntimeException e) {
            sink.onError(e);
            return () -> { };
        }
    }

    private String modelFor(Tier tier) {
        AgentProperties.Tier t = props.getTier();
        return switch (tier == null ? Tier.STANDARD : tier) {
            case FAST -> t.getFast();
            case STANDARD -> t.getStandard();
            case ADVANCED -> t.getAdvanced();
        };
    }
}
