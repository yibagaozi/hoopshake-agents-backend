package com.cnsportiot.cloud.ops;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.CreateOpsChatSessionRequest;
import com.cnsportiot.cloud.ops.dto.OpsChatRequests.OpsChatAskRequest;
import com.cnsportiot.cloud.ops.entity.OpsChatSession;
import com.cnsportiot.cloud.ops.repository.OpsChatSessionRepository;
import com.cnsportiot.cloud.ops.service.impl.OpsChatServiceImpl;
import com.cnsportiot.cloud.repository.ChatMessageRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 运维对话编排的运维特有部分:会话归属(owner_account_id)、提问前置(LLM 降级 / 限流)与拒绝顺序
 * SSE 全链路已由学生侧 ChatServiceIntegrationTest 覆盖,此处只测 OPS 分支不变量
 */
class OpsChatServiceImplTest {

    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000ad");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000bd");
    private static final UUID SESSION = UUID.fromString("cccccccc-0000-0000-0000-0000000000c5");

    private OpsChatSessionRepository sessionRepo;
    private ChatMessageRepository messageRepo;
    private LlmGateway llmGateway;
    private TokenBucketRateLimiter rateLimiter;
    private LlmStreamBulkhead bulkhead;
    private AgentProperties props;
    private OpsChatServiceImpl service;

    @BeforeEach
    void setup() {
        sessionRepo = mock(OpsChatSessionRepository.class);
        messageRepo = mock(ChatMessageRepository.class);
        llmGateway = mock(LlmGateway.class);
        rateLimiter = mock(TokenBucketRateLimiter.class);
        bulkhead = mock(LlmStreamBulkhead.class);
        props = new AgentProperties();
        service = new OpsChatServiceImpl(sessionRepo, messageRepo, llmGateway,
                new ToolRegistry(new ArrayList<>()), props, rateLimiter, bulkhead);
    }

    private OpsChatSession owned() {
        return OpsChatSession.create(ADMIN, "巡检");
    }

    @Test void createSession_persistsWithOwnerAndTitle() {
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service.createSession(new CreateOpsChatSessionRequest("  夜间巡检  "), ADMIN);

        ArgumentCaptor<OpsChatSession> cap = ArgumentCaptor.forClass(OpsChatSession.class);
        verify(sessionRepo).save(cap.capture());
        assertThat(cap.getValue().getOwnerAccountId()).isEqualTo(ADMIN);
        assertThat(cap.getValue().getTitle()).isEqualTo("夜间巡检");   // 去空白
        assertThat(cap.getValue().isDeleted()).isFalse();
    }

    @Test void foreignSession_denied() {
        OpsChatSession foreign = OpsChatSession.create(OTHER, "别人的");
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.deleteSession(SESSION, ADMIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.DATA_SCOPE_DENIED));
    }

    @Test void deletedSession_denied() {
        OpsChatSession s = owned();
        s.setDeleted(true);
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.listMessages(SESSION, ADMIN, 0, 20))
                .isInstanceOf(BusinessException.class);
    }

    @Test void ask_llmDisabled_returnsLlmUnavailable() {
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(owned()));
        when(llmGateway.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.ask(SESSION, new OpsChatAskRequest("现在健康吗"), ADMIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
        verifyNoInteractions(bulkhead);   // 降级早于占用任何资源
    }

    @Test void ask_rateLimited_returnsRateLimited_beforeAnyDbWrite() {
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(owned()));
        when(llmGateway.isEnabled()).thenReturn(true);
        when(rateLimiter.tryAcquire(ADMIN)).thenReturn(false);   // 桶空
        assertThatThrownBy(() -> service.ask(SESSION, new OpsChatAskRequest("现在健康吗"), ADMIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        verify(messageRepo, never()).save(any());   // 拒绝在建任何消息之前
        verifyNoInteractions(bulkhead);
    }
}
