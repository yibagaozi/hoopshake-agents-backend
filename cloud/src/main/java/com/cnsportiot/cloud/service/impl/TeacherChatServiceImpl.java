package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.entity.TeacherChatSession;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import com.cnsportiot.cloud.dto.request.TeacherChatRequests.CreateTeacherChatSessionRequest;
import com.cnsportiot.cloud.dto.request.TeacherChatRequests.TeacherChatAskRequest;
import com.cnsportiot.cloud.dto.response.ChatDtos.*;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.repository.ChatMessageRepository;
import com.cnsportiot.cloud.repository.TeacherChatSessionRepository;
import com.cnsportiot.cloud.service.TeacherChatService;
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
 * 教师分析对话实现(TEACHING_ANALYST)。复用 LlmGateway/SSE 生命周期,但:
 * 无 RAG、无路由(恒教师分析师、STANDARD 档)、工具取 {@link ScopeKind#TEACHER},
 * 上下文用 {@link ToolContext#teacher}(目标学生/课程由模型给、经 TeacherScopeGuardHook 校验)
 * 会话存 teacher_chat_session,消息复用 chat_message
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherChatServiceImpl implements TeacherChatService {

    private static final int HISTORY_WINDOW = 10;
    private static final long HEARTBEAT_SECONDS = 15L;

    private final TeacherChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final AgentProperties props;

    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "teacher-chat-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    // 会话 CRUD

    @Override
    public ChatSessionResponse createSession(CreateTeacherChatSessionRequest request, UUID teacherAccountId) {
        TeacherChatSession s = sessionRepo.save(TeacherChatSession.create(teacherAccountId,
                request.title() == null || request.title().isBlank() ? null : request.title().strip()));
        return toSessionDto(s);
    }

    @Override
    public PageResponse<ChatSessionResponse> listSessions(UUID teacherAccountId, int page, int size) {
        var pageable = PageResponses.toPageable(page, size,
                org.springframework.data.domain.Sort.by("updatedAt").descending());
        return PageResponses.from(
                sessionRepo.findByTeacherIdAndDeletedFalse(teacherAccountId, pageable), this::toSessionDto);
    }

    @Override
    public PageResponse<ChatMessageResponse> listMessages(UUID sessionId, UUID teacherAccountId, int page, int size) {
        requireOwnedSession(sessionId, teacherAccountId);
        var pageable = PageResponses.toPageable(page, size);
        return PageResponses.from(
                messageRepo.findByChatSessionIdOrderByCreatedAtAsc(sessionId, pageable), this::toMessageDto);
    }

    @Override
    public ChatSessionResponse rename(UUID sessionId, UUID teacherAccountId, String title) {
        TeacherChatSession s = requireOwnedSession(sessionId, teacherAccountId);
        s.setTitle(title.strip());
        return toSessionDto(sessionRepo.save(s));
    }

    @Override
    public void deleteSession(UUID sessionId, UUID teacherAccountId) {
        TeacherChatSession s = requireOwnedSession(sessionId, teacherAccountId);
        s.setDeleted(true);
        sessionRepo.save(s);
    }

    // 提问(SSE)

    @Override
    public SseEmitter ask(UUID sessionId, TeacherChatAskRequest request, UUID teacherAccountId) {
        requireOwnedSession(sessionId, teacherAccountId);
        if (!llmGateway.isEnabled()) {
            throw new BusinessException(ErrorCode.LLM_UNAVAILABLE);
        }

        ActiveRun previous = activeRuns.remove(sessionId);
        if (previous != null) {
            cancelAndFinalize(previous, "interrupted", null);
        }

        List<LlmGateway.Turn> history = loadHistory(sessionId);
        messageRepo.save(newMessage(sessionId, MessageRole.USER, request.content()));
        ChatMessage shell = messageRepo.save(newMessage(sessionId, MessageRole.ASSISTANT, ""));
        touchTitleIfBlank(sessionId, request.content());

        SseEmitter emitter = new SseEmitter(0L);
        send(emitter, "meta", new ChatMetaEvent(shell.getId(), sessionId));

        Tier tier = Tier.STANDARD;
        String system = buildSystemPrompt();

        List<AgentTool> tools = props.getTools().isExposeInChat()
                ? toolRegistry.byScope(ScopeKind.TEACHER) : List.of();
        ToolContext toolContext = ToolContext.teacher(teacherAccountId, sessionId, tier);

        ActiveRun run = new ActiveRun(sessionId, shell.getId(), emitter);
        activeRuns.put(sessionId, run);
        emitter.onCompletion(() -> activeRuns.remove(sessionId, run));
        emitter.onTimeout(() -> cancelAndFinalize(run, "stop", null));
        emitter.onError(t -> cancelAndFinalize(run, "stop", t));

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
        if (run.finished.get()) {
            hb.cancel(true);
        }
        return emitter;
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

        try {
            messageRepo.findById(run.assistantMessageId).ifPresent(m -> {
                m.setContent(run.buffer.toString());
                messageRepo.save(m);
            });
        } catch (RuntimeException e) {
            log.error("落 assistant 消息失败 teacherSessionId={}", run.sessionId, e);
        }

        if (error != null) {
            log.warn("教师对话流出错 sessionId={}: {}", run.sessionId, error.toString());
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
               你是 HOOPSHAKE 的教师分析师(Teaching Analyst),帮助教师分析所辖学生/班级/课程的训练数据并给出可执行建议。

               取数规则:
               - 只能分析教师名下课程的学生;需要 studentId 时先用 list_lesson_students(lessonId) 或 resolve_student(keyword) 获取,不要臆造 id。
               - 分析某节课/某班/整门课程前,先用 list_my_lessons 拿到 lessonId。
               - 群体问题(整班、多课次课程、多名学生)优先用 get_group_summary / find_common_issues,不要逐个拉原始数据自己算。
               - 单人用 get_student_session(单次)/ get_student_trend(走势)。

               分析套路:
               - 个人复盘:trend + 最近 session + 共性问题 → 进步叙述 + 建议。
               - 班级诊断:group_summary + common_issues(lessonId)→ 健康度 + Top 训练建议。
               - 课程纵览:group_summary + common_issues(lessonIds 多课次)→ 走势 + 顽固共性问题。

               输出:引用具体数字;指出共性薄弱点与改善方向;结尾给 2-3 条可执行的训练/教学建议。数据不足时说明,不要编造。
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

    private TeacherChatSession requireOwnedSession(UUID sessionId, UUID teacherAccountId) {
        TeacherChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
        if (s.isDeleted() || !s.getTeacherId().equals(teacherAccountId)) {
            throw BusinessException.dataScopeDenied();
        }
        return s;
    }

    private ChatSessionResponse toSessionDto(TeacherChatSession s) {
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

        ActiveRun(UUID sessionId, UUID assistantMessageId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.assistantMessageId = assistantMessageId;
            this.emitter = emitter;
        }
    }
}

