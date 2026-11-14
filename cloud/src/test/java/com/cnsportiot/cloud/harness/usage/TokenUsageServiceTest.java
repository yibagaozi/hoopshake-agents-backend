package com.cnsportiot.cloud.harness.usage;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.TokenUsageRecord;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.TokenUsageRecordRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 周配额闸门与记账。配额按"流水聚合"判定,不存可变计数器,故这里只需喂不同的已用量。
 */
class TokenUsageServiceTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-0000-0000-0000-000000000001");

    private TokenUsageRecordRepository usageRepo;
    private AccountRepository accountRepository;
    private AgentProperties props;
    private TokenUsageService svc;

    @BeforeEach
    void setup() {
        usageRepo = mock(TokenUsageRecordRepository.class);
        accountRepository = mock(AccountRepository.class);
        props = new AgentProperties();
        props.getQuota().setWeeklyTokensStudent(1000);
        props.getQuota().setWeeklyTokensTeacher(5000);
        props.getQuota().setWeeklyTokensAdmin(0);   // 不限
        svc = new TokenUsageService(usageRepo, accountRepository, props);
    }

    // ---- 配额闸门 ----

    @Test void underLimit_passes() {
        when(usageRepo.sumTotalSince(eq(ACCOUNT), any())).thenReturn(999L);
        assertThatCode(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.STUDENT)).doesNotThrowAnyException();
    }

    @Test void atLimit_rejects() {
        when(usageRepo.sumTotalSince(eq(ACCOUNT), any())).thenReturn(1000L);
        assertThatThrownBy(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.STUDENT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TOKEN_QUOTA_EXCEEDED));
    }

    @Test void overLimit_rejects() {
        when(usageRepo.sumTotalSince(eq(ACCOUNT), any())).thenReturn(4321L);
        assertThatThrownBy(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.STUDENT))
                .isInstanceOf(BusinessException.class);
    }

    @Test void roleLimitsAreSeparate_teacherHasHeadroomWhereStudentWouldFail() {
        when(usageRepo.sumTotalSince(eq(ACCOUNT), any())).thenReturn(1500L);
        assertThatThrownBy(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.STUDENT))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.TEACHER)).doesNotThrowAnyException();
    }

    @Test void zeroLimit_meansUnlimited_neverQueriesUsage() {
        assertThatCode(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.ADMIN)).doesNotThrowAnyException();
        verify(usageRepo, never()).sumTotalSince(any(), any());
    }

    @Test void quotaDisabled_passesEvenWhenOver() {
        props.getQuota().setEnabled(false);
        assertThatCode(() -> svc.ensureWithinWeeklyQuota(ACCOUNT, Role.STUDENT)).doesNotThrowAnyException();
        verify(usageRepo, never()).sumTotalSince(any(), any());
    }

    @Test void nullAccount_passes() {
        assertThatCode(() -> svc.ensureWithinWeeklyQuota(null, Role.STUDENT)).doesNotThrowAnyException();
    }

    // ---- 记账 ----

    @Test void record_persistsSplitInputOutput() {
        UUID session = UUID.randomUUID();
        svc.record(ACCOUNT, Role.STUDENT, UsageSource.STUDENT_CHAT,
                new LlmGateway.Usage(120, 80, "glm-5.2", false), Tier.STANDARD, session, "stop");

        ArgumentCaptor<TokenUsageRecord> cap = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(usageRepo).save(cap.capture());
        TokenUsageRecord r = cap.getValue();
        assertThat(r.getAccountId()).isEqualTo(ACCOUNT);
        assertThat(r.getPromptTokens()).isEqualTo(120);
        assertThat(r.getCompletionTokens()).isEqualTo(80);
        assertThat(r.getTotalTokens()).isEqualTo(200);
        assertThat(r.isEstimated()).isFalse();
        assertThat(r.getSource()).isEqualTo(UsageSource.STUDENT_CHAT);
        assertThat(r.getModel()).isEqualTo("glm-5.2");
        assertThat(r.getTier()).isEqualTo("STANDARD");
        assertThat(r.getSessionId()).isEqualTo(session);
        assertThat(r.getFinishReason()).isEqualTo("stop");
        assertThat(r.getOccurredAt()).isNotNull();
    }

    @Test void record_nullUsage_isNoop() {
        svc.record(ACCOUNT, Role.STUDENT, UsageSource.STUDENT_CHAT, null, Tier.FAST, null, "stop");
        verify(usageRepo, never()).save(any());
    }

    /** 记账失败绝不能把对话带崩。 */
    @Test void record_repositoryFailure_isSwallowed() {
        when(usageRepo.save(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> svc.record(ACCOUNT, Role.STUDENT, UsageSource.STUDENT_CHAT,
                new LlmGateway.Usage(1, 1, "m", true), Tier.FAST, null, "stop"))
                .doesNotThrowAnyException();
    }

    // ---- 前端提示快照 ----

    @Test void myUsage_reportsRemainingAndWarning() {
        props.getQuota().setWarnRatio(0.8);
        when(usageRepo.sumDetailSince(eq(ACCOUNT), any())).thenReturn(sum(500, 300, 800, 4));

        var r = svc.myUsage(ACCOUNT, Role.STUDENT);

        assertThat(r.promptTokens()).isEqualTo(500);
        assertThat(r.completionTokens()).isEqualTo(300);
        assertThat(r.totalTokens()).isEqualTo(800);
        assertThat(r.calls()).isEqualTo(4);
        assertThat(r.limit()).isEqualTo(1000L);
        assertThat(r.remaining()).isEqualTo(200L);
        assertThat(r.ratio()).isEqualTo(0.8);
        assertThat(r.warning()).isTrue();     // 恰好到告警线
        assertThat(r.exceeded()).isFalse();
        assertThat(r.resetAt()).isAfter(r.windowStart());
    }

    @Test void myUsage_exceededWhenAtLimit() {
        when(usageRepo.sumDetailSince(eq(ACCOUNT), any())).thenReturn(sum(600, 400, 1000, 5));
        var r = svc.myUsage(ACCOUNT, Role.STUDENT);
        assertThat(r.exceeded()).isTrue();
        assertThat(r.remaining()).isZero();
    }

    @Test void myUsage_unlimitedRole_hasNullLimitAndRemaining() {
        when(usageRepo.sumDetailSince(eq(ACCOUNT), any())).thenReturn(sum(10, 10, 20, 1));
        var r = svc.myUsage(ACCOUNT, Role.ADMIN);
        assertThat(r.limit()).isNull();
        assertThat(r.remaining()).isNull();
        assertThat(r.warning()).isFalse();
        assertThat(r.exceeded()).isFalse();
    }

    /** 本周还没用过:聚合返回 null 也要给出 0 而不是 NPE。 */
    @Test void myUsage_noRecords_returnsZeros() {
        when(usageRepo.sumDetailSince(eq(ACCOUNT), any())).thenReturn(null);
        var r = svc.myUsage(ACCOUNT, Role.STUDENT);
        assertThat(r.totalTokens()).isZero();
        assertThat(r.remaining()).isEqualTo(1000L);
        assertThat(r.exceeded()).isFalse();
    }

    // ---- 窗口 ----

    /** 周窗口起点必须是周一 00:00,且早于此刻。 */
    @Test void weekWindow_startsMonday() {
        when(usageRepo.sumDetailSince(eq(ACCOUNT), any())).thenReturn(sum(0, 0, 0, 0));
        var r = svc.myUsage(ACCOUNT, Role.STUDENT);
        assertThat(r.windowStart().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(r.windowStart().toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(r.windowStart()).isBeforeOrEqualTo(OffsetDateTime.now());
        assertThat(r.resetAt()).isEqualTo(r.windowStart().plusWeeks(1));
    }

    private static TokenUsageRecordRepository.UsageSum sum(long p, long c, long t, long calls) {
        return new TokenUsageRecordRepository.UsageSum() {
            @Override public long getPromptTokens() { return p; }
            @Override public long getCompletionTokens() { return c; }
            @Override public long getTotalTokens() { return t; }
            @Override public long getCalls() { return calls; }
        };
    }
}
