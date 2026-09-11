package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.ActionClip;
import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.dto.request.StudentDataRequests.UpdateProfileRequest;
import com.cnsportiot.cloud.dto.response.StudentDataDtos.*;
import com.cnsportiot.cloud.repository.*;
import com.cnsportiot.contracts.enums.DominantHand;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** StudentDataServiceImpl 单测:归属闸、trend 指标解析/过滤/截断、severity 解析、profile 增量、overview 空数据 */
class StudentDataServiceImplTest {

    private static final UUID SID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID CLIP = UUID.fromString("33333333-0000-0000-0000-000000000001");

    private ActionClipRepository clipRepo;
    private SessionAggregateRepository aggRepo;
    private InstantFeedbackRepository feedbackRepo;
    private TrainingSessionRepository sessionRepo;
    private LessonRepository lessonRepo;
    private StudentRepository studentRepo;
    private StudentDataServiceImpl svc;

    @BeforeEach
    void setup() {
        clipRepo = mock(ActionClipRepository.class);
        aggRepo = mock(SessionAggregateRepository.class);
        feedbackRepo = mock(InstantFeedbackRepository.class);
        sessionRepo = mock(TrainingSessionRepository.class);
        lessonRepo = mock(LessonRepository.class);
        studentRepo = mock(StudentRepository.class);
        svc = new StudentDataServiceImpl(clipRepo, aggRepo, feedbackRepo, sessionRepo, lessonRepo, studentRepo);
    }

    // ---- 归属闸(clipDetail / requireOwnsSession)----

    @Test void clipDetail_notFound() {
        when(clipRepo.findById(CLIP)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.clipDetail(SID, CLIP), ErrorCode.NOT_FOUND);
    }

    @Test void clipDetail_notOwner_dataScopeDenied() {
        ActionClip c = mock(ActionClip.class);
        when(c.getStudentId()).thenReturn(UUID.randomUUID());   // 别人的片段
        when(clipRepo.findById(CLIP)).thenReturn(Optional.of(c));
        assertBusiness(() -> svc.clipDetail(SID, CLIP), ErrorCode.DATA_SCOPE_DENIED);
    }

    @Test void clipDetail_owner_ok() {
        ActionClip c = mock(ActionClip.class);
        when(c.getStudentId()).thenReturn(SID);
        when(c.getId()).thenReturn(CLIP);
        when(c.getSessionId()).thenReturn(SESSION);
        when(c.getActionType()).thenReturn("layup");
        when(clipRepo.findById(CLIP)).thenReturn(Optional.of(c));
        ClipDetail d = svc.clipDetail(SID, CLIP);
        assertThat(d.clipId()).isEqualTo(CLIP);
        assertThat(d.actionType()).isEqualTo("layup");
    }

    @Test void sessionDetail_notOwner_dataScopeDenied() {
        when(clipRepo.existsBySessionIdAndStudentId(SESSION, SID)).thenReturn(false);
        assertBusiness(() -> svc.sessionDetail(SID, SESSION), ErrorCode.DATA_SCOPE_DENIED);
    }

    @Test void listClips_notOwner_dataScopeDenied() {
        when(clipRepo.existsBySessionIdAndStudentId(SESSION, SID)).thenReturn(false);
        assertBusiness(() -> svc.listClips(SID, SESSION, null, 0, 20), ErrorCode.DATA_SCOPE_DENIED);
    }

    // listClips:actionType 有无走不同查询

    @Test void listClips_blankActionType_usesUnfilteredQuery() {
        when(clipRepo.existsBySessionIdAndStudentId(SESSION, SID)).thenReturn(true);
        when(clipRepo.findBySessionIdAndStudentId(eq(SESSION), eq(SID), any())).thenReturn(Page.empty());
        svc.listClips(SID, SESSION, "  ", 0, 20);
        verify(clipRepo).findBySessionIdAndStudentId(eq(SESSION), eq(SID), any());
        verify(clipRepo, never()).findBySessionIdAndStudentIdAndActionType(any(), any(), any(), any());
    }

    @Test void listClips_withActionType_usesFilteredQuery_trimmed() {
        when(clipRepo.existsBySessionIdAndStudentId(SESSION, SID)).thenReturn(true);
        when(clipRepo.findBySessionIdAndStudentIdAndActionType(eq(SESSION), eq(SID), eq("layup"), any()))
                .thenReturn(Page.empty());
        svc.listClips(SID, SESSION, " layup ", 0, 20);
        verify(clipRepo).findBySessionIdAndStudentIdAndActionType(eq(SESSION), eq(SID), eq("layup"), any());
    }

    // listFeedback:severity 解析

    @Test void listFeedback_invalidSeverity_fallsBackToUnfiltered() {
        when(clipRepo.existsBySessionIdAndStudentId(SESSION, SID)).thenReturn(true);
        when(feedbackRepo.findByStudentIdAndSessionId(eq(SID), eq(SESSION), any())).thenReturn(Page.empty());
        svc.listFeedback(SID, SESSION, "not-a-severity", 0, 20);
        verify(feedbackRepo).findByStudentIdAndSessionId(eq(SID), eq(SESSION), any());
        verify(feedbackRepo, never()).findByStudentIdAndSessionIdAndSeverity(any(), any(), any(), any());
    }

    @Test void listFeedback_validSeverity_caseInsensitive_usesFiltered() {
        when(clipRepo.existsBySessionIdAndStudentId(SESSION, SID)).thenReturn(true);
        when(feedbackRepo.findByStudentIdAndSessionIdAndSeverity(eq(SID), eq(SESSION), eq(FeedbackSeverity.MAJOR), any()))
                .thenReturn(Page.empty());
        svc.listFeedback(SID, SESSION, "major", 0, 20);   // 小写也能解析
        verify(feedbackRepo).findByStudentIdAndSessionIdAndSeverity(eq(SID), eq(SESSION), eq(FeedbackSeverity.MAJOR), any());
    }

    // trend:默认指标 / 过滤 / 嵌套 / 截断 / 空值跳过

    @Test void trend_defaultMetricFgPct_allActions() {
        List<Object[]> data = List.<Object[]>of(
                row("layup", Map.of("fg_pct", 0.5), SESSION),
                row("shot", Map.of("fg_pct", 0.7), UUID.randomUUID()));
        when(aggRepo.findWithTimeByStudent(SID)).thenReturn(data);
        TrendResponse r = svc.trend(SID, null, null, 0);
        assertThat(r.metric()).isEqualTo("fg_pct");
        assertThat(r.points()).extracting(TrendPoint::value).containsExactly(0.5, 0.7);
    }

    @Test void trend_filtersByActionType() {
        List<Object[]> data = List.<Object[]>of(
                row("layup", Map.of("fg_pct", 0.5), SESSION),
                row("shot", Map.of("fg_pct", 0.7), UUID.randomUUID()));
        when(aggRepo.findWithTimeByStudent(SID)).thenReturn(data);
        TrendResponse r = svc.trend(SID, "layup", null, 0);
        assertThat(r.points()).extracting(TrendPoint::value).containsExactly(0.5);
    }

    @Test void trend_nestedReleaseMetric() {
        List<Object[]> data = List.<Object[]>of(
                row("shot", Map.of("mean_release_angles", Map.of("elbow", 95.0)), SESSION));
        when(aggRepo.findWithTimeByStudent(SID)).thenReturn(data);
        TrendResponse r = svc.trend(SID, null, "release.elbow", 0);
        assertThat(r.points()).extracting(TrendPoint::value).containsExactly(95.0);
    }

    @Test void trend_skipsRowsMissingMetric() {
        List<Object[]> data = List.<Object[]>of(
                row("shot", Map.of("attempts", 10), SESSION),        // 无 fg_pct → 跳过
                row("shot", Map.of("fg_pct", 0.6), UUID.randomUUID()));
        when(aggRepo.findWithTimeByStudent(SID)).thenReturn(data);
        TrendResponse r = svc.trend(SID, null, "fg_pct", 0);
        assertThat(r.points()).hasSize(1);
        assertThat(r.points().get(0).value()).isEqualTo(0.6);
    }

    @Test void trend_limitKeepsMostRecentTail() {
        List<Object[]> data = List.<Object[]>of(
                row("shot", Map.of("fg_pct", 0.1), UUID.randomUUID()),
                row("shot", Map.of("fg_pct", 0.2), UUID.randomUUID()),
                row("shot", Map.of("fg_pct", 0.3), UUID.randomUUID()));
        when(aggRepo.findWithTimeByStudent(SID)).thenReturn(data);
        TrendResponse r = svc.trend(SID, null, "fg_pct", 2);
        assertThat(r.points()).extracting(TrendPoint::value).containsExactly(0.2, 0.3);   // 保留最后 2 个
    }

    // ---- updateProfile ----

    @Test void updateProfile_notFound() {
        when(studentRepo.findById(SID)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.updateProfile(SID, new UpdateProfileRequest(null, null, null)),
                ErrorCode.NOT_FOUND);
    }

    @Test void updateProfile_incremental_onlySetsProvidedFields() {
        Student s = mock(Student.class);
        when(studentRepo.findById(SID)).thenReturn(Optional.of(s));
        when(s.getId()).thenReturn(SID);
        when(s.getStudentNo()).thenReturn("S1");
        when(s.getDominantHand()).thenReturn(DominantHand.LEFT);
        when(s.getHeightCm()).thenReturn(new BigDecimal("170.0"));
        when(studentRepo.findDisplayNameByStudentId(SID)).thenReturn(Optional.of("Kid"));

        ProfileResponse r = svc.updateProfile(SID, new UpdateProfileRequest(DominantHand.LEFT, new BigDecimal("170.0"), null));
        verify(s).setDominantHand(DominantHand.LEFT);
        verify(s).setHeightCm(new BigDecimal("170.0"));
        verify(s, never()).setLegLengthCm(any());   // null 不设
        verify(studentRepo).save(s);
        assertThat(r.displayName()).isEqualTo("Kid");
        assertThat(r.dominantHand()).isEqualTo("LEFT");
    }

    // overview:空数据兜底

    @Test void overview_emptyData_nullsAndZeros() {
        when(studentRepo.findDisplayNameByStudentId(SID)).thenReturn(Optional.of("Kid"));
        when(clipRepo.countByStudentId(SID)).thenReturn(0L);
        when(clipRepo.countDistinctSessionByStudentId(SID)).thenReturn(0L);
        when(sessionRepo.findSessionsByStudent(eq(SID), any(), any(), any())).thenReturn(Page.empty());
        when(clipRepo.findActionStatsByStudentId(SID)).thenReturn(List.of());

        OverviewResponse r = svc.overview(SID);
        assertThat(r.totalSessions()).isZero();
        assertThat(r.totalClips()).isZero();
        assertThat(r.lastSessionAt()).isNull();
        assertThat(r.focusCheckpoint()).isNull();
        assertThat(r.recentSessions()).isEmpty();
        assertThat(r.actionTypeStats()).isEmpty();
        assertThat(r.weekly().sessions()).isZero();
        assertThat(r.weekly().clips()).isZero();
    }

    // helpers

    private static Object[] row(String actionType, Map<String, Object> stats, UUID sessionId) {
        SessionAggregate agg = mock(SessionAggregate.class);
        lenient().when(agg.getActionType()).thenReturn(actionType);
        lenient().when(agg.getStats()).thenReturn(stats);
        lenient().when(agg.getSessionId()).thenReturn(sessionId);
        return new Object[]{agg, OffsetDateTime.now()};
    }

    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code));
    }
}

