package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.entity.ChatSession;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import com.cnsportiot.cloud.dto.request.ChatRequests.ChatAskRequest;
import com.cnsportiot.cloud.dto.request.ChatRequests.CreateChatSessionRequest;
import com.cnsportiot.cloud.dto.response.ChatDtos.*;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.rag.RagStore;
import com.cnsportiot.cloud.harness.rag.Snippet;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.repository.ChatMessageRepository;
import com.cnsportiot.cloud.repository.ChatSessionRepository;
import com.cnsportiot.cloud.service.ChatService;
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
 * 学生对话实现
 * SSE 生命周期参考 LessonLiveStreamServiceImpl:注册 completion/timeout/error 防泄漏、15s 心跳。
 * 流式在 LlmGateway 内部线程回调;中断通过 {@link ActiveRun} 句柄取消并收尾。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final int HISTORY_WINDOW = 10;
    private static final long HEARTBEAT_SECONDS = 15L;

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final LlmGateway llmGateway;
    private final RagStore ragStore;
    private final ToolRegistry toolRegistry;
    private final AgentProperties props;

    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "chat-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /** 每会话最多一个进行中的生成,用于中断/收尾 */
    private final ConcurrentMap<UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    // 会话 CRUD

    @Override
    public ChatSessionResponse createSession(CreateChatSessionRequest request, UUID studentId) {
        ChatSession session = ChatSession.create(studentId, null,
                request.title() == null || request.title().isBlank() ? null : request.title().strip());
        session = sessionRepo.save(session);
        return toSessionDto(session);
    }

    @Override
    public PageResponse<ChatSessionResponse> listSessions(UUID studentId, int page, int size) {
        var pageable = PageResponses.toPageable(page, size,
                org.springframework.data.domain.Sort.by("updatedAt").descending());
        return PageResponses.from(sessionRepo.findByStudentIdAndDeletedFalse(studentId, pageable), this::toSessionDto);
    }

    @Override
    public PageResponse<ChatMessageResponse> listMessages(UUID sessionId, UUID studentId, int page, int size) {
        requireOwnedSession(sessionId, studentId);
        var pageable = PageResponses.toPageable(page, size);
        return PageResponses.from(
                messageRepo.findByChatSessionIdOrderByCreatedAtAsc(sessionId, pageable), this::toMessageDto);
    }

    @Override
    public ChatSessionResponse rename(UUID sessionId, UUID studentId, String title) {
        ChatSession session = requireOwnedSession(sessionId, studentId);
        session.setTitle(title.strip());
        return toSessionDto(sessionRepo.save(session));
    }

    @Override
    public void deleteSession(UUID sessionId, UUID studentId) {
        ChatSession session = requireOwnedSession(sessionId, studentId);
        session.setDeleted(true);
        sessionRepo.save(session);   // 软删,不物理删消息
    }

    @Override
    public SseEmitter ask(UUID sessionId, ChatAskRequest request, UUID studentId, UUID accountId) {
        requireOwnedSession(sessionId, studentId);
        if (!llmGateway.isEnabled()) {
            throw new BusinessException(ErrorCode.LLM_UNAVAILABLE);
        }

        // 已有进行中的生成 → 先收尾旧的(中断)
        ActiveRun previous = activeRuns.remove(sessionId);
        if (previous != null) {
            cancelAndFinalize(previous, "interrupted", null);
        }

        // 上下文窗口(取本轮新消息之前的历史)
        List<LlmGateway.Turn> history = loadHistory(sessionId);

        // 落 USER + ASSISTANT 空壳
        messageRepo.save(newMessage(sessionId, MessageRole.USER, request.content()));
        ChatMessage shell = messageRepo.save(newMessage(sessionId, MessageRole.ASSISTANT, ""));
        touchTitleIfBlank(sessionId, request.content());

        SseEmitter emitter = new SseEmitter(0L);
        send(emitter, "meta", new ChatMetaEvent(shell.getId(), sessionId));

        // 模式决议(5.1):锚定训练 → STRUCTURED,否则 OPEN。锚定的存在性校验(读 action_clip)
        // 属算法侧数据,本里程碑隔离,仅用其存在与否决定档位/注入量
        boolean anchored = request.trainingSessionId() != null;
        Tier tier = anchored ? Tier.STANDARD : Tier.FAST;
        int maxInjected = anchored
                ? props.getRag().getMaxInjectedStructured()
                : props.getRag().getMaxInjectedOpen();

        String system = buildSystemPrompt(retrieve(request.content(), maxInjected));

        ActiveRun run = new ActiveRun(sessionId, shell.getId(), emitter);
        activeRuns.put(sessionId, run);

        emitter.onCompletion(() -> activeRuns.remove(sessionId, run));
        emitter.onTimeout(() -> cancelAndFinalize(run, "stop", null));
        emitter.onError(t -> cancelAndFinalize(run, "stop", t));

        // 工具:开关打开则把注册的学生工具交给本轮;权威身份从 token 而来,不由模型选择
        List<AgentTool> tools = props.getTools().isExposeInChat() ? toolRegistry.all() : List.of();
        ToolContext toolContext = new ToolContext(accountId, studentId, sessionId, tier);

        LlmGateway.StreamRequest llmReq =
                new LlmGateway.StreamRequest(system, history, request.content(), tier, null, tools, toolContext);
        run.handle = llmGateway.stream(llmReq, new LlmGateway.StreamSink() {
            @Override public void onDelta(String text) {
                if (run.finished.get()) return;
                run.buffer.append(text);
                send(emitter, "delta", new ChatDeltaEvent(text));
            }
            @Override public void onToolEvent(String name, String status, String label) {
                if (run.finished.get()) return;
                send(emitter, "tool", new ChatToolEvent(name, status, label));
            }
            @Override public void onComplete(String finishReason) {
                finishRun(run, finishReason, null);
            }
            @Override public void onError(Throwable error) {
                finishRun(run, "stop", error);
            }
        });

        ScheduledFuture<?> hb = heartbeat.scheduleAtFixedRate(
                () -> send(emitter, "ping", "ping"), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        run.heartbeat = hb;
        if (run.finished.get()) {   // 同步收尾(如禁用/立即失败)已发生
            hb.cancel(true);
        }
        return emitter;
    }

    @Override
    public void interrupt(UUID sessionId, UUID studentId) {
        requireOwnedSession(sessionId, studentId);
        ActiveRun run = activeRuns.get(sessionId);
        if (run != null) {
            cancelAndFinalize(run, "interrupted", null);
        }
        // 无进行中的生成 → 幂等成功
    }

    // 收尾

    private void cancelAndFinalize(ActiveRun run, String reason, Throwable error) {
        if (run.handle != null) {
            try { run.handle.cancel(); } catch (RuntimeException ignore) { }
        }
        finishRun(run, reason, error);
    }

    /** 收尾:落 assistant 正文 + 发 done/error + 关流。幂等(AtomicBoolean 守卫) */
    private void finishRun(ActiveRun run, String reason, Throwable error) {
        if (!run.finished.compareAndSet(false, true)) {
            return;
        }
        if (run.heartbeat != null) {
            run.heartbeat.cancel(true);
        }
        activeRuns.remove(run.sessionId, run);

        try {
            messageRepo.findById(run.assistantMessageId).ifPresent(m -> {
                m.setContent(run.buffer.toString());
                messageRepo.save(m);
            });
        } catch (RuntimeException e) {
            log.error("落 assistant 消息失败 sessionId={}", run.sessionId, e);
        }

        if (error != null) {
            log.warn("对话流出错 sessionId={}: {}", run.sessionId, error.toString());
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

    private List<Snippet> retrieve(String query, int maxInjected) {
        if (maxInjected <= 0 || !ragStore.isEnabled()) {
            return List.of();
        }
        List<Snippet> hits = ragStore.search(query, props.getRag().getTopK(), props.getRag().getSimilarityThreshold());
        return hits.size() > maxInjected ? hits.subList(0, maxInjected) : hits;
    }

    private String buildSystemPrompt(List<Snippet> snippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 HOOPSHAKE 篮球训练助手(Skill Coach)。只做动作要点讲解与训练原理问答,")
          .append("不给学生打分、不排名;谈进步只跟他自己比。回答简洁、可执行、面向青少年球员。\n");
        if (snippets.isEmpty()) {
            sb.append("\n(本次未检索到专门参考资料,请基于篮球常识谨慎回答,不要编造具体测量数据。)\n");
        } else {
            sb.append("\n下面是从知识库检索到的参考资料,请优先据此回答;资料不足处基于常识谨慎补充,不要编造数据:\n");
            int i = 1;
            for (Snippet s : snippets) {
                sb.append("\n【参考").append(i++).append("】\n").append(s.toContext()).append('\n');
            }
        }
        return sb.toString();
    }

    private List<LlmGateway.Turn> loadHistory(UUID sessionId) {
        List<ChatMessage> recent = messageRepo.findByChatSessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, HISTORY_WINDOW));
        List<LlmGateway.Turn> turns = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {   // 翻转为正序
            ChatMessage m = recent.get(i);
            if (m.getContent() == null || m.getContent().isBlank()) continue;   // 跳过空壳
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
        return ChatMessage.builder()
                .chatSessionId(sessionId)
                .role(role)
                .content(content)
                .build();
    }

    private ChatSession requireOwnedSession(UUID sessionId, UUID studentId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
        if (session.isDeleted() || !session.getStudentId().equals(studentId)) {
            throw BusinessException.dataScopeDenied();   // 40301(已删视同不可见)
        }
        return session;
    }

    private ChatSessionResponse toSessionDto(ChatSession s) {
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

    /** 一次进行中的生成的可变状态 */
    private static final class ActiveRun {
        final UUID sessionId;
        final UUID assistantMessageId;
        final SseEmitter emitter;
        final StringBuilder buffer = new StringBuilder();
        final AtomicBoolean finished = new AtomicBoolean(false);
        volatile LlmGateway.StreamHandle handle;
        volatile ScheduledFuture<?> heartbeat;

        ActiveRun(UUID sessionId, UUID assistantMessageId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.assistantMessageId = assistantMessageId;
            this.emitter = emitter;
        }
    }
}
