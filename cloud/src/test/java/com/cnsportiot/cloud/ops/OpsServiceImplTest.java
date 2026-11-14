package com.cnsportiot.cloud.ops;

import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.dto.OpsDtos.*;
import com.cnsportiot.cloud.ops.evaluator.EdgeHealthEvaluator;
import com.cnsportiot.cloud.ops.repository.EdgeDeviceRepository;
import com.cnsportiot.cloud.ops.service.impl.OpsServiceImpl;
import com.cnsportiot.cloud.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 运维聚合:Agent 表现近窗行映射/比率、系统健康韧性快照映射(均不依赖 DB)。 */
class OpsServiceImplTest {

    private final ChatMessageRepository chatMessageRepo = mock(ChatMessageRepository.class);
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final LlmStreamBulkhead bulkhead = new LlmStreamBulkhead(4);
    private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(1, 60);

    private OpsServiceImpl service() {
        return new OpsServiceImpl(
                mock(StudentRepository.class), mock(AccountRepository.class), mock(LessonRepository.class),
                mock(TrainingSessionRepository.class), mock(ActionClipRepository.class),
                mock(InstantFeedbackRepository.class), mock(ChatSessionRepository.class),
                chatMessageRepo, mock(KnowledgeDocumentRepository.class), mock(EdgeDeviceRepository.class),
                gateway, bulkhead, rateLimiter, new EdgeHealthEvaluator(new OpsProperties()), new OpsProperties());
    }

    @Test void agentQuality_mapsRowAndRates() {
        // [answered, degraded, ragHit, avgChars, toolOk, toolDeny, toolError]
        Object[] row = {10L, 2L, 6L, 214.7, 40L, 1L, 3L};
        when(chatMessageRepo.aggregateAgentQualitySince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Collections.singletonList(row));

        AgentQualityResponse q = service().agentQuality(24);
        assertThat(q.windowHours()).isEqualTo(24);
        assertThat(q.answeredRuns()).isEqualTo(10);
        assertThat(q.degradedRuns()).isEqualTo(2);
        assertThat(q.degradedRate()).isEqualTo(0.2);
        assertThat(q.ragHitRuns()).isEqualTo(6);
        assertThat(q.ragHitRate()).isEqualTo(0.6);
        assertThat(q.avgAnswerChars()).isEqualTo(214.7);
        assertThat(q.tool().ok()).isEqualTo(40);
        assertThat(q.toolErrorRate()).isEqualTo(Math.round(3.0 / 44 * 1000.0) / 1000.0);
    }

    @Test void agentQuality_emptyWindow_zerosAndNullRates() {
        when(chatMessageRepo.aggregateAgentQualitySince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());   // 无数据
        AgentQualityResponse q = service().agentQuality(12);
        assertThat(q.answeredRuns()).isZero();
        assertThat(q.degradedRate()).isNull();     // 分母 0 → null
        assertThat(q.ragHitRate()).isNull();
        assertThat(q.avgAnswerChars()).isNull();
        assertThat(q.toolErrorRate()).isNull();
    }

    @Test void agentQuality_queryFailure_degradesToEmpty() {
        when(chatMessageRepo.aggregateAgentQualitySince(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));
        AgentQualityResponse q = service().agentQuality(6);
        assertThat(q.answeredRuns()).isZero();
        assertThat(q.tool().ok()).isZero();
    }

    @Test void systemHealth_mapsResilienceSnapshot() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.circuitState()).thenReturn(OptionalInt.of(2));   // OPEN
        bulkhead.tryAcquire();
        bulkhead.tryAcquire();                                        // active=2, available=2
        rateLimiter.tryAcquire("acct");
        rateLimiter.tryAcquire("acct");                              // 第二次被拒(容量1)

        SystemHealthResponse s = service().systemHealth();
        assertThat(s.llmEnabled()).isTrue();
        assertThat(s.circuit().state()).isEqualTo("OPEN");
        assertThat(s.llmStreams().active()).isEqualTo(2);
        assertThat(s.llmStreams().available()).isEqualTo(2);
        assertThat(s.llmStreams().max()).isEqualTo(4);
        assertThat(s.askRateLimit().rejected()).isEqualTo(1);
        assertThat(s.askRateLimit().trackedKeys()).isEqualTo(1);
    }

    @Test void systemHealth_circuitNullWhenGatewayNotBreaking() {
        when(gateway.isEnabled()).thenReturn(false);
        when(gateway.circuitState()).thenReturn(OptionalInt.empty());
        SystemHealthResponse s = service().systemHealth();
        assertThat(s.llmEnabled()).isFalse();
        assertThat(s.circuit().state()).isNull();
    }
}
