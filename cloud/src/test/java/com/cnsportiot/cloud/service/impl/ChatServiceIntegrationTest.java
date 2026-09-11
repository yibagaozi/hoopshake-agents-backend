package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.common.BaseEntity;
import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.entity.ChatSession;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import com.cnsportiot.cloud.dto.request.ChatRequests.ChatAskRequest;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import com.cnsportiot.cloud.harness.rag.Chunk;
import com.cnsportiot.cloud.harness.rag.RagStore;
import com.cnsportiot.cloud.harness.rag.Snippet;
import com.cnsportiot.cloud.harness.router.RouterService;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.harness.tool.impl.GetActionDetailTool;
import com.cnsportiot.cloud.harness.tool.impl.GetInstantFeedbackLogTool;
import com.cnsportiot.cloud.harness.tool.impl.GetProgressTrendTool;
import com.cnsportiot.cloud.harness.tool.impl.GetRecentClipsTool;
import com.cnsportiot.cloud.harness.tool.impl.GetSessionSummaryTool;
import com.cnsportiot.cloud.harness.tool.port.PlaceholderStudentDataAdapter;
import com.cnsportiot.cloud.repository.ChatMessageRepository;
import com.cnsportiot.cloud.repository.ChatSessionRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成:学生对话编排全链路(不需 Spring 上下文 / DB / GLM)。
 * 真实的 RouterService + ToolRegistry + ChatServiceImpl,DB 用 Mockito 内存化,LLM 用可控 fake。
 * 验证:路由意图→旋钮生效、RAG 注入按意图开关、工具轨迹与决策落进 chat_message.detail、越权拒绝
 */
class ChatServiceIntegrationTest {

    private static final UUID STUDENT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_STUDENT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");

    private ChatServiceImpl chat;
    private ChatSessionRepository sessionRepo;
    private ChatMessageRepository messageRepo;
    private final Map<UUID, ChatMessage> messageStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        sessionRepo = mock(ChatSessionRepository.class);
        messageRepo = mock(ChatMessageRepository.class);
        messageStore.clear();

        AgentProperties props = new AgentProperties();
        FakeGateway gateway = new FakeGateway();
        RouterService router = new RouterService(gateway, props);

        var port = new PlaceholderStudentDataAdapter();
        List<AgentTool> tools = List.of(
                new GetRecentClipsTool(port), new GetSessionSummaryTool(port),
                new GetInstantFeedbackLogTool(port), new GetProgressTrendTool(port),
                new GetActionDetailTool(port));
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(tools));

        chat = new ChatServiceImpl(sessionRepo, messageRepo, gateway, new FakeRagStore(), registry, router, props,
                new TokenBucketRateLimiter(100, 6000), new LlmStreamBulkhead(100));

        // 会话归属:属于 STUDENT
        ChatSession session = ChatSession.create(STUDENT, null, "t");
        setId(session, SESSION);
        lenient().when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(session));
        lenient().when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // 空历史
        lenient().when(messageRepo.findByChatSessionIdOrderByCreatedAtDesc(eq(SESSION), any(Pageable.class)))
                .thenReturn(List.of());
        // save 分配 id 并入内存;findById 从内存取
        lenient().when(messageRepo.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (m.getId() == null) {
                setId(m, UUID.randomUUID());
            }
            messageStore.put(m.getId(), m);
            return m;
        });
        lenient().when(messageRepo.findById(any()))
                .thenAnswer(i -> Optional.ofNullable(messageStore.get(i.getArgument(0))));
    }

    @AfterEach
    void tearDown() {
        chat.shutdown();
    }

    private static final UUID SESSION = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private ChatMessage assistant() {
        return messageStore.values().stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> route(ChatMessage m) {
        return (Map<String, Object>) m.getDetail().get("route");
    }

    @Test
    void actionTechnique_injectsRag_exposesTools_persistsDetail() {
        chat.ask(SESSION, new ChatAskRequest("投篮时辅助手怎么放?", null), STUDENT, ACCOUNT);

        assertThat(messageStore.values()).hasSize(2);          // USER + ASSISTANT
        ChatMessage a = assistant();
        assertThat(a.getContent()).isEqualTo("答:完毕");        // fake 拼接的两段 delta

        Map<String, Object> detail = a.getDetail();
        assertThat(route(a)).containsEntry("intent", "ACTION_TECHNIQUE").containsEntry("source", "rule");
        assertThat((List<?>) detail.get("rag")).hasSize(1);    // useRag=true → 注入 1 片段
        assertThat((List<?>) detail.get("tools")).hasSize(1);  // 暴露工具 → fake 触发 1 次工具事件
        assertThat(detail).containsEntry("finishReason", "stop");
    }

    @Test
    void smalltalk_noRag_noTools() {
        chat.ask(SESSION, new ChatAskRequest("你好呀", null), STUDENT, ACCOUNT);

        ChatMessage a = assistant();
        assertThat(route(a)).containsEntry("intent", "SMALLTALK");
        assertThat((List<?>) a.getDetail().get("rag")).isEmpty();     // useRag=false
        assertThat((List<?>) a.getDetail().get("tools")).isEmpty();   // 不暴露工具
    }

    @Test
    void anchoredTraining_isTrainingReview() {
        chat.ask(SESSION, new ChatAskRequest("看看", UUID.randomUUID()), STUDENT, ACCOUNT);

        assertThat(route(assistant())).containsEntry("intent", "TRAINING_REVIEW")
                .containsEntry("source", "anchored");
    }

    @Test
    void foreignSession_isDataScopeDenied() {
        ChatSession others = ChatSession.create(OTHER_STUDENT, null, "x");
        setId(others, SESSION);
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> chat.ask(SESSION, new ChatAskRequest("你好", null), STUDENT, ACCOUNT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.DATA_SCOPE_DENIED));
    }

    @Test
    void interrupt_whenNoActiveRun_isIdempotent() {
        chat.ask(SESSION, new ChatAskRequest("你好", null), STUDENT, ACCOUNT);   // 同步完成,无进行中
        chat.interrupt(SESSION, STUDENT);                                        // 不应抛
    }

    // ---- 反射设置实体 id(无 setter)----
    private static void setId(Object entity, UUID id) {
        try {
            Field f = BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 可控 LLM:两段 delta,暴露工具时触发一次工具事件,然后完成。 */
    private static final class FakeGateway implements LlmGateway {
        @Override public boolean isEnabled() { return true; }

        @Override public StreamHandle stream(StreamRequest request, StreamSink sink) {
            sink.onDelta("答:");
            if (request.tools() != null && !request.tools().isEmpty()) {
                sink.onToolEvent("get_recent_clips", "ok", "看片段");
            }
            sink.onDelta("完毕");
            sink.onComplete("stop");
            return () -> { };
        }

        @Override public Optional<String> complete(CompletionRequest request) {
            return Optional.empty();   // 本测试用例都命中规则,不触发分类
        }
    }

    /** 恒返回一个片段的向量库。 */
    private static final class FakeRagStore implements RagStore {
        @Override public boolean isEnabled() { return true; }

        @Override public List<String> load(String docId, List<Chunk> chunks, Map<String, Object> baseMetadata) {
            return List.of();
        }

        @Override public void deleteChunks(List<String> chunkIds) { }

        @Override public List<Snippet> search(String query, int topK, double similarityThreshold) {
            return List.of(new Snippet("辅助手只扶不推", "辅助手", List.of("罚篮", "辅助手"), "doc-1", 0.8));
        }
    }
}
