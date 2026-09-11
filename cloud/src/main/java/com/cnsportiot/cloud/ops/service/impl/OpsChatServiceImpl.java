package com.cnsportiot.cloud.ops.service.impl;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import com.cnsportiot.cloud.dto.response.ChatDtos.*;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.CreateOpsChatSessionRequest;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.OpsChatAskRequest;
import com.cnsportiot.cloud.ops.entity.OpsChatSession;
import com.cnsportiot.cloud.ops.repository.OpsChatSessionRepository;
import com.cnsportiot.cloud.ops.service.OpsChatService;
import com.cnsportiot.cloud.repository.ChatMessageRepository;
import com.cnsportiot.contracts.common.PageResponse;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运维诊断对话实现(Ops Assistant)。与 {@code TeacherChatServiceImpl} 同构:复用 LlmGateway/SSE 生命周期、
 * 限流/舱壁/SSE 硬超时/熔断退避,但——无 RAG、无路由(恒 STANDARD 档)、工具取 {@link ScopeKind#OPS}、
 * 上下文用 {@link ToolContext#ops}(全局只读,两道 scope 闸天然放行)。会话存 ops_chat_session,消息复用 chat_message。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsChatServiceImpl implements OpsChatService {

    private static final int HISTORY_WINDOW = 10;
    private static final long HEARTBEAT_SECONDS = 15L;

    private final OpsChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final AgentProperties props;
    private final TokenBucketRateLimiter askRateLimiter;
    private final LlmStreamBulkhead bulkhead;

    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ops-chat-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    // 会话 CRUD

    @Override
    public ChatSessionResponse createSession(CreateOpsChatSessionRequest request, UUID ownerAccountId) {
        OpsChatSession s = sessionRepo.save(OpsChatSession.create(ownerAccountId,
                request.title() == null || request.title().isBlank() ? null : request.title().strip()));
        return toSessionDto(s);
    }

    @Override
    public PageResponse<ChatSessionResponse> listSessions(UUID ownerAccountId, int page, int size) {
        var pageable = PageResponses.toPageable(page, size,
                org.springframework.data.domain.Sort.by("updatedAt").descending());
        return PageResponses.from(
                sessionRepo.findByOwnerAccountIdAndDeletedFalse(ownerAccountId, pageable), this::toSessionDto);
    }

    @Override
    public PageResponse<ChatMessageResponse> listMessages(UUID sessionId, UUID ownerAccountId, int page, int size) {
        requireOwnedSession(sessionId, ownerAccountId);
        var pageable = PageResponses.toPageable(page, size);
        return PageResponses.from(
                messageRepo.findByChatSessionIdOrderByCreatedAtAsc(sessionId, pageable), this::toMessageDto);
    }

    @Override
    public ChatSessionResponse rename(UUID sessionId, UUID ownerAccountId, String title) {
        OpsChatSession s = requireOwnedSession(sessionId, ownerAccountId);
        s.setTitle(title.strip());
        return toSessionDto(sessionRepo.save(s));
    }

    @Override
    public void deleteSession(UUID sessionId, UUID ownerAccountId) {
        OpsChatSession s = requireOwnedSession(sessionId, ownerAccountId);
        s.setDeleted(true);
        sessionRepo.save(s);
    }

    // 提问(SSE)

    @Override
    public SseEmitter ask(UUID sessionId, OpsChatAskRequest request, UUID ownerAccountId) {
        requireOwnedSession(sessionId, ownerAccountId);
        if (!llmGateway.isEnabled()) {
            throw new BusinessException(ErrorCode.LLM_UNAVAILABLE);
        }
        // 按账号限流(对话最贵),拒绝在建任何 DB 记录之前
        if (props.getResilience().getRateLimit().isEnabled() && !askRateLimiter.tryAcquire(ownerAccountId)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "提问过于频繁,请稍后再试。");
        }

        ActiveRun previous = activeRuns.remove(sessionId);
        if (previous != null) {
            cancelAndFinalize(previous, "interrupted", null);
        }

        // 全局 LLM 流并发舱壁(与学生/教师端共享配额):满载快速失败
        if (!bulkhead.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "当前对话并发已满,请稍后再试。");
        }
        ActiveRun run = null;
        boolean started = false;
        try {
            List<LlmGateway.Turn> history = loadHistory(sessionId);
            messageRepo.save(newMessage(sessionId, MessageRole.USER, request.content()));
            ChatMessage shell = messageRepo.save(newMessage(sessionId, MessageRole.ASSISTANT, ""));
            touchTitleIfBlank(sessionId, request.content());

            SseEmitter emitter = new SseEmitter(props.getResilience().getSse().getHardTimeoutMillis());
            send(emitter, "meta", new ChatMetaEvent(shell.getId(), sessionId));

            Tier tier = Tier.STANDARD;
            String system = buildSystemPrompt();

            List<AgentTool> tools = props.getTools().isExposeInChat()
                    ? toolRegistry.byScope(ScopeKind.OPS) : List.of();
            ToolContext toolContext = ToolContext.ops(ownerAccountId, sessionId, tier);

            run = new ActiveRun(sessionId, shell.getId(), emitter);
            run.release = bulkhead::release;
            final ActiveRun r = run;
            activeRuns.put(sessionId, run);
            emitter.onCompletion(() -> activeRuns.remove(sessionId, r));
            emitter.onTimeout(() -> cancelAndFinalize(r, "stop", null));
            emitter.onError(t -> cancelAndFinalize(r, "stop", t));

            LlmGateway.StreamRequest llmReq =
                    new LlmGateway.StreamRequest(system, history, request.content(), tier, null, tools, toolContext);
            r.handle = llmGateway.stream(llmReq, new LlmGateway.StreamSink() {
                @Override public void onDelta(String text) {
                    if (r.finished.get()) return;
                    r.buffer.append(text);
                    send(emitter, "delta", new ChatDeltaEvent(text));
                }
                @Override public void onToolEvent(String name, String status, String label) {
                    if (r.finished.get()) return;
                    send(emitter, "tool", new ChatToolEvent(name, status, label));
                }
                @Override public void onComplete(String finishReason) {
                    finishRun(r, finishReason, null);
                }
                @Override public void onError(Throwable error) {
                    finishRun(r, "stop", error);
                }
            });
            started = true;

            ScheduledFuture<?> hb = heartbeat.scheduleAtFixedRate(
                    () -> send(emitter, "ping", "ping"), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            r.heartbeat = hb;
            if (r.finished.get()) {
                hb.cancel(true);
            }
            return emitter;
        } catch (RuntimeException e) {
            if (!started) {
                if (run != null) {
                    activeRuns.remove(sessionId, run);
                    run.releaseBulkhead();
                } else {
                    bulkhead.release();
                }
            }
            throw e;
        }
    }

    // 收尾

    private void cancelAndFinalize(ActiveRun run, String reason, Throwable error) {
        if (run.handle != null) {
            try { run.handle.cancel(); } catch (RuntimeException ignore) { }
        }
        finishRun(run, reason, error);
    }

    private void finishRun(ActiveRun run, String reason, Throwable error) {
        if (!run.finished.compareAndSet(false, true)) {
            return;
        }
        if (run.heartbeat != null) {
            run.heartbeat.cancel(true);
        }
        activeRuns.remove(run.sessionId, run);
        run.releaseBulkhead();   // 归还全局并发许可(幂等)

        try {
            messageRepo.findById(run.assistantMessageId).ifPresent(m -> {
                m.setContent(run.buffer.toString());
                messageRepo.save(m);
            });
        } catch (RuntimeException e) {
            log.error("落 assistant 消息失败 opsSessionId={}", run.sessionId, e);
        }

        if (error != null) {
            log.warn("运维对话流出错 sessionId={}: {}", run.sessionId, error.toString());
            send(run.emitter, "error", Map.of(
                    "code", ErrorCode.LLM_UNAVAILABLE.code(),
                    "message", "AI 服务暂不可用,请稍后重试"));
        } else {
            send(run.emitter, "done",
                    new ChatDoneEvent(run.assistantMessageId, reason, null, List.of()));
        }
        try { run.emitter.complete(); } catch (RuntimeException ignore) { }
    }

    // 辅助

    private String buildSystemPrompt() {
        return """
               你是 HOOPSHAKE 运维助手(Ops Assistant),只读诊断,面向运维/管理员。

               取数规则:
               - 先用 get_system_overview 看全局;深入某一面再调对应工具,不要凭记忆报数字。
               - 错误码含义用 lookup_error_code(直读枚举,永远最新);配置/阈值用 get_runtime_config(读运行时活值)。
               - 业务量用 get_business_counts;Agent 表现用 get_agent_quality;系统健康用 get_system_health。
               - 设备排障用 list_edge_devices(可传 OFFLINE/STALE 只看异常)+ get_edge_device 看单盒子明细。

               诊断套路:
               - 健康巡检:overview →(有异常)system_health + list_edge_devices(OFFLINE)→ 一句话结论 + 异常清单 + 优先处置。
               - 容量/业务量:business_counts → 规模现状(趋势曲线引导去 Grafana/看板)。
               - Agent 质量:agent_quality + system_health → 降级/工具错误归因(RAG?越权?熔断打开?)+ 建议。
               - 场边排障:list_edge_devices(OFFLINE|STALE)+ get_edge_device +(有错误码)lookup_error_code → 定位 + 可能原因 + 处置步骤。
               - 错误码/配置:lookup_error_code + get_runtime_config → 讲清 4xxxx/5xxxx 并关联当前阈值(如"42900 限流:当前 burst=8/每分钟 30")。

               输出:引用具体数字与阈值;给健康判断(正常/关注/告警)+ 归因 + 2-3 条可执行的下一步。
               边界:你只诊断与建议,绝不执行重启/清缓存/切模型/改配置等动作;涉及动作,明确让运维在台上人工操作。
                    数据不足或工具报错时如实说明,不编造。
               """;
    }

    private List<LlmGateway.Turn> loadHistory(UUID sessionId) {
        List<ChatMessage> recent = messageRepo.findByChatSessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, HISTORY_WINDOW));
        List<LlmGateway.Turn> turns = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessage m = recent.get(i);
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            turns.add(new LlmGateway.Turn(
                    m.getRole() == MessageRole.ASSISTANT ? "assistant" : "user", m.getContent()));
        }
        return turns;
    }

    private void touchTitleIfBlank(UUID sessionId, String firstUserText) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            if (s.getTitle() == null || s.getTitle().isBlank()) {
                String t = firstUserText.strip();
                s.setTitle(t.length() > 20 ? t.substring(0, 20) : t);
                sessionRepo.save(s);
            }
        });
    }

    private ChatMessage newMessage(UUID sessionId, MessageRole role, String content) {
        return ChatMessage.builder().chatSessionId(sessionId).role(role).content(content).build();
    }

    private OpsChatSession requireOwnedSession(UUID sessionId, UUID ownerAccountId) {
        OpsChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
        if (s.isDeleted() || !s.getOwnerAccountId().equals(ownerAccountId)) {
            throw BusinessException.dataScopeDenied();
        }
        return s;
    }

    private ChatSessionResponse toSessionDto(OpsChatSession s) {
        return new ChatSessionResponse(s.getId(), s.getTitle(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private ChatMessageResponse toMessageDto(ChatMessage m) {
        return new ChatMessageResponse(m.getId(), m.getRole(), m.getContent(), m.getTokenUsage(), m.getCreatedAt());
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开;下次 send 或收尾时清理
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
    }

    private static final class ActiveRun {
        final UUID sessionId;
        final UUID assistantMessageId;
        final SseEmitter emitter;
        final StringBuilder buffer = new StringBuilder();
        final AtomicBoolean finished = new AtomicBoolean(false);
        volatile LlmGateway.StreamHandle handle;
        volatile ScheduledFuture<?> heartbeat;
        volatile Runnable release;       // 释放舱壁许可;null 表示未占用
        private final AtomicBoolean released = new AtomicBoolean(false);

        ActiveRun(UUID sessionId, UUID assistantMessageId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.assistantMessageId = assistantMessageId;
            this.emitter = emitter;
        }

        /** 归还舱壁许可,至多一次 */
        void releaseBulkhead() {
            Runnable r = release;
            if (r != null && released.compareAndSet(false, true)) {
                r.run();
            }
        }
    }
}

