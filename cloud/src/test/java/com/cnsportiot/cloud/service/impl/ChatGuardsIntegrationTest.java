package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.entity.ChatSession;
import com.cnsportiot.cloud.dto.request.ChatRequests.ChatAskRequest;
import com.cnsportiot.cloud.harness.llm.CircuitOpenException;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.rag.Chunk;
import com.cnsportiot.cloud.harness.rag.RagStore;
import com.cnsportiot.cloud.harness.rag.Snippet;
import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import com.cnsportiot.cloud.harness.router.RouterService;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.harness.usage.TokenUsageService;
import com.cnsportiot.cloud.repository.ChatMessageRepository;
import com.cnsportiot.cloud.repository.ChatSessionRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 对话入口的三道闸:**按账号限流**、**周用量配额**、**全局并发舱壁**。
 *
 * <p>三者各自的状态机已有单测({@code TokenBucketRateLimiterTest} / {@code LlmStreamBulkheadTest} /
 * {@code CircuitBreakerTest});本类补的是它们**接在 {@code ask} 上之后**的行为,即真正会伤到用户的那两条不变量:
 * <ol>
 *   <li><b>拒绝必须发生在落库之前</b>——否则被拒的那次会在学生的"对话记录"里留下一条零消息的空会话
 *       (前端已实测复现过这类残留);</li>
 *   <li><b>舱壁许可必须归还</b>——熔断/出错时若漏还,并发位会被一次次蚕食,最后整个对话端点永久 429,
 *       且没有任何报错指向真正的原因。</li>
 * </ol>
 * 这两条都不是单测能覆盖的:它们是"编排顺序"和"资源生命周期"的性质。
 */
class ChatGuardsIntegrationTest {

    private static final UUID STUDENT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID SESSION = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    /** 第二个会话:舱壁是全局闸,只有跨会话才能验出它 */
    private static final UUID SESSION2 = UUID.fromString("cccccccc-0000-0000-0000-000000000004");

    private ChatSessionRepository sessionRepo;
    private ChatMessageRepository messageRepo;
    private TokenUsageService usage;
    private AgentProperties props;
    private ControllableGateway gateway;

    @BeforeEach
    void setup() {
        sessionRepo = mock(ChatSessionRepository.class);
        messageRepo = mock(ChatMessageRepository.class);
        usage = mock(TokenUsageService.class);
        props = new AgentProperties();
        gateway = new ControllableGateway();

        ChatSession session = ChatSession.create(STUDENT, null, "t");
        ReflectionTestUtils.setField(session, "id", SESSION);
        lenient().when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(session));

        ChatSession session2 = ChatSession.create(STUDENT, null, "t2");
        ReflectionTestUtils.setField(session2, "id", SESSION2);
        lenient().when(sessionRepo.findById(SESSION2)).thenReturn(Optional.of(session2));
        lenient().when(messageRepo.findByChatSessionIdOrderByCreatedAtDesc(eq(SESSION2), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(messageRepo.findByChatSessionIdOrderByCreatedAtDesc(eq(SESSION), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(messageRepo.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (m.getId() == null) {
                ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
            }
            return m;
        });
        lenient().when(messageRepo.findById(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        if (chat != null) {
            chat.shutdown();
        }
    }

    private ChatServiceImpl chat;

    /** 按给定闸门参数装一个 ChatServiceImpl。 */
    private ChatServiceImpl build(TokenBucketRateLimiter limiter, LlmStreamBulkhead bulkhead) {
        chat = new ChatServiceImpl(sessionRepo, messageRepo, gateway, new NoopRag(),
                new ToolRegistry(new ArrayList<>()), new RouterService(gateway, props), props,
                limiter, bulkhead, usage);
        return chat;
    }

    private void ask() {
        chat.ask(SESSION, new ChatAskRequest("投篮时肘部怎么放?", null), STUDENT, ACCOUNT);
    }

    // ---------- 1) 按账号限流 ----------

    /** 桶容量 1:第二次提问被限流,错误码 42900。 */
    @Test void rateLimiter_secondAskIsRejected() {
        build(new TokenBucketRateLimiter(1, 1), new LlmStreamBulkhead(10));
        props.getResilience().getRateLimit().setEnabled(true);

        assertThatCode(this::ask).doesNotThrowAnyException();   // 第一次:消耗掉唯一令牌

        assertThatThrownBy(this::ask)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    /**
     * 被限流的那次**一条消息都不能落库**。
     * 否则学生的对话记录里会多出一条零消息的"未命名对话"。
     */
    @Test void rateLimiter_rejectionWritesNothing() {
        build(new TokenBucketRateLimiter(1, 1), new LlmStreamBulkhead(10));
        props.getResilience().getRateLimit().setEnabled(true);
        ask();
        clearInvocations(messageRepo);

        assertThatThrownBy(this::ask).isInstanceOf(BusinessException.class);

        verify(messageRepo, never()).save(any());
    }

    /** 限流开关关掉后不拦截(同一个空桶也放行)。 */
    @Test void rateLimiter_disabled_doesNotBlock() {
        build(new TokenBucketRateLimiter(1, 1), new LlmStreamBulkhead(10));
        props.getResilience().getRateLimit().setEnabled(false);
        ask();
        assertThatCode(this::ask).doesNotThrowAnyException();
    }

    // ---------- 2) 周用量配额 ----------

    /** 配额超限:42911,且同样不落库。 */
    @Test void quota_exceeded_rejectsBeforeAnyWrite() {
        build(new TokenBucketRateLimiter(100, 6000), new LlmStreamBulkhead(10));
        doThrow(new BusinessException(ErrorCode.TOKEN_QUOTA_EXCEEDED))
                .when(usage).ensureWithinWeeklyQuota(any(), any());

        assertThatThrownBy(this::ask)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TOKEN_QUOTA_EXCEEDED));

        verify(messageRepo, never()).save(any());
        assertThat(gateway.calls.get()).isZero();   // 更没打到模型
    }

    /** 顺序:限流在配额之前——桶空时压根不该去查配额(省一次 DB 聚合)。 */
    @Test void rateLimiter_firesBeforeQuota() {
        build(new TokenBucketRateLimiter(1, 1), new LlmStreamBulkhead(10));
        props.getResilience().getRateLimit().setEnabled(true);
        ask();
        clearInvocations(usage);

        assertThatThrownBy(this::ask).isInstanceOf(BusinessException.class);

        verify(usage, never()).ensureWithinWeeklyQuota(any(), any());
    }

    // ---------- 3) 全局并发舱壁 ----------

    /**
     * 只有 1 个并发位、第一路仍在生成时,**另一个会话**的提问被舱壁挡下(42900)。
     * 注意必须用不同会话:舱壁是全局闸,而同会话追问会先中断上一轮(见下一条)。
     */
    @Test void bulkhead_whenFull_rejectsAskFromAnotherSession() {
        build(new TokenBucketRateLimiter(100, 6000), new LlmStreamBulkhead(1));
        gateway.hold = true;    // 不收尾,占住许可

        ask();                  // 会话 A 占满唯一并发位

        assertThatThrownBy(() ->
                chat.ask(SESSION2, new ChatAskRequest("我也问一句", null), STUDENT, ACCOUNT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    /**
     * 同一会话追问:先把上一轮收尾为 interrupted 并归还许可,所以**不会**被舱壁挡。
     * 这条语义值得钉住——否则学生在同一个会话里连问两句就会莫名其妙吃到"并发已满"。
     */
    @Test void bulkhead_sameSessionFollowUp_interruptsPreviousInsteadOfRejecting() {
        build(new TokenBucketRateLimiter(100, 6000), new LlmStreamBulkhead(1));
        gateway.hold = true;

        ask();   // 占住许可

        assertThatCode(() ->
                chat.ask(SESSION, new ChatAskRequest("再问一句", null), STUDENT, ACCOUNT))
                .as("同会话追问应中断上一轮,而非被舱壁拒绝")
                .doesNotThrowAnyException();
    }

    /** 生成正常收尾后许可归还,后续提问能继续。 */
    @Test void bulkhead_permitReturnedAfterCompletion() {
        LlmStreamBulkhead bulkhead = new LlmStreamBulkhead(1);
        build(new TokenBucketRateLimiter(100, 6000), bulkhead);
        gateway.hold = false;   // 同步跑完

        ask();
        assertThatCode(this::ask).doesNotThrowAnyException();
    }

    // ---------- 4) 熔断:失败路径不能漏还许可 ----------

    /**
     * 熔断打开时网关直接 {@code onError(CircuitOpenException)}。这条路径**必须归还舱壁许可**——
     * 漏还的话每熔断一次就少一个并发位,最后整个对话端点永久 429,而且报错指向"并发已满"
     * 这个与真正原因(模型不可用)毫不相干的方向,极难排查。
     */
    @Test void circuitOpen_releasesBulkheadPermit() {
        build(new TokenBucketRateLimiter(100, 6000), new LlmStreamBulkhead(1));
        gateway.failWith = new CircuitOpenException();

        ask();   // 熔断错误经 SSE error 事件下发,ask 本身不抛

        // 许可若没还,这一次会被舱壁挡下
        gateway.failWith = null;
        assertThatCode(this::ask)
                .as("熔断失败后并发位应已归还")
                .doesNotThrowAnyException();
    }

    /** 连续熔断失败也不该累积吃掉并发位。 */
    @Test void repeatedCircuitOpen_doesNotLeakPermits() {
        build(new TokenBucketRateLimiter(100, 6000), new LlmStreamBulkhead(1));
        gateway.failWith = new CircuitOpenException();

        for (int i = 0; i < 5; i++) {
            ask();
        }

        gateway.failWith = null;
        assertThatCode(this::ask).doesNotThrowAnyException();
    }

    // ---------- 测试替身 ----------

    /** 可控网关:能同步跑完、能挂住不收尾(占许可)、能以指定异常失败。 */
    private static final class ControllableGateway implements LlmGateway {
        volatile boolean hold;
        volatile RuntimeException failWith;
        final AtomicLong calls = new AtomicLong();

        @Override public boolean isEnabled() { return true; }

        @Override public StreamHandle stream(StreamRequest request, StreamSink sink) {
            calls.incrementAndGet();
            RuntimeException f = failWith;
            if (f != null) {
                sink.onError(f);
                return () -> { };
            }
            if (hold) {
                return () -> { };      // 不回调终态:许可一直被占着
            }
            sink.onDelta("好的");
            sink.onUsage(new Usage(10, 5, "fake", true));
            sink.onComplete("stop");
            return () -> { };
        }

        @Override public Optional<String> complete(CompletionRequest request) {
            return Optional.empty();   // 路由退回规则,不触发分类
        }
    }

    /** 不做检索的向量库。 */
    private static final class NoopRag implements RagStore {
        @Override public boolean isEnabled() { return false; }
        @Override public List<String> load(String docId, List<Chunk> chunks, Map<String, Object> baseMetadata) {
            return List.of();
        }
        @Override public void deleteChunks(List<String> chunkIds) { }
        @Override public List<Snippet> search(String query, int topK, double threshold) { return List.of(); }
    }
}
