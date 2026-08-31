package com.cnsportiot.cloud.harness.adapter.spring;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.config.AgentProperties.ModelSpec;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link LlmGateway} 的 Spring AI 实现 */
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

            ModelSpec spec = props.specForTier(request.tier());
            var ob = OpenAiChatOptions.builder();
            ob.model(spec.getModel());
            if (request.maxTokens() != null) {
                ob.maxTokens(request.maxTokens());
            }
            if (spec.reasoningEffort() != null) {
                ob.reasoningEffort(spec.reasoningEffort());
            }

            ChatClient.ChatClientRequestSpec req = chatClient.prompt()
                    .system(request.system() == null ? "" : request.system())
                    .messages(history)
                    .user(request.user())
                    .options(ob);

            Disposable disposable = req.stream().content()
                    .subscribe(sink::onDelta,
                            sink::onError,
                            () -> sink.onComplete("stop"));
            return disposable::dispose;
        } catch (RuntimeException e) {
            sink.onError(e);
            return () -> { };
        }
    }

    @Override
    public Optional<String> complete(CompletionRequest request) {
        try {
            ModelSpec spec = props.specForTier(request.tier());
            var ob = OpenAiChatOptions.builder();
            ob.model(spec.getModel());
            if (request.maxTokens() != null) {
                ob.maxTokens(request.maxTokens());
            }
            if (spec.reasoningEffort() != null) {
                ob.reasoningEffort(spec.reasoningEffort());
            }
            String content = chatClient.prompt()
                    .system(request.system() == null ? "" : request.system())
                    .user(request.user() == null ? "" : request.user())
                    .options(ob)
                    .call()
                    .content();
            return Optional.ofNullable(content);
        } catch (RuntimeException e) {
            return Optional.empty();   // 分类/短补全失败不断链,调用方退回规则
        }
    }
}