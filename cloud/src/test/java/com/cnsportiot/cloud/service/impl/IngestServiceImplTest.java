package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.ActionClip;
import com.cnsportiot.cloud.domain.entity.ReidGallery;
import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.cloud.dto.request.IngestRequests.*;
import com.cnsportiot.cloud.dto.response.IngestDtos.*;
import com.cnsportiot.cloud.repository.*;
import com.cnsportiot.contracts.enums.GalleryStatus;
import com.cnsportiot.contracts.enums.SessionStatus;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** IngestServiceImpl 单测:会话状态只进不退、批量入库幂等/去重/拒绝、gallery 换代、名单拉取。 */
class IngestServiceImplTest {

    private static final UUID SESSION = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID STU_A = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    private static final UUID STU_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID LESSON = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    private TrainingSessionRepository sessionRepo;
    private ActionClipRepository clipRepo;
    private InstantFeedbackRepository feedbackRepo;
    private ReidGalleryRepository galleryRepo;
    private StudentRepository studentRepo;
    private LessonEnrollmentRepository enrollmentRepo;
    private SessionAggregateRepository aggRepo;
    private IngestServiceImpl ingest;

    @BeforeEach
    void setup() {
        sessionRepo = mock(TrainingSessionRepository.class);
        clipRepo = mock(ActionClipRepository.class);
        feedbackRepo = mock(InstantFeedbackRepository.class);
        galleryRepo = mock(ReidGalleryRepository.class);
        studentRepo = mock(StudentRepository.class);
        enrollmentRepo = mock(LessonEnrollmentRepository.class);
        aggRepo = mock(SessionAggregateRepository.class);
        ingest = new IngestServiceImpl(sessionRepo, clipRepo, feedbackRepo, galleryRepo,
                studentRepo, enrollmentRepo, aggRepo);
    }

    // ================= upsertSession(状态只进不退)=================

    @Test void upsertSession_new_createsWithGivenIdAsPrimaryKey() {
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.empty());
        ArgumentCaptor<TrainingSession> cap = ArgumentCaptor.forClass(TrainingSession.class);
        when(sessionRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));

        IngestAckResponse ack = ingest.upsertSession(SESSION, upsertReq(SessionStatus.CREATED));

        assertThat(ack.applied()).isTrue();
        assertThat(ack.duplicated()).isFalse();
        assertThat(ack.currentStatus()).isEqualTo(SessionStatus.CREATED);
        assertThat(cap.getValue().getId()).isEqualTo(SESSION);   // 反射把 sessionId 设成主键
    }

    @Test void upsertSession_advance_updatesStatus() {
        TrainingSession existing = mock(TrainingSession.class);
        when(existing.getStatus()).thenReturn(SessionStatus.RECORDED);
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(existing));

        IngestAckResponse ack = ingest.upsertSession(SESSION, upsertReq(SessionStatus.SCORED));

        assertThat(ack.applied()).isTrue();
        assertThat(ack.duplicated()).isFalse();
        assertThat(ack.currentStatus()).isEqualTo(SessionStatus.SCORED);
        verify(existing).setStatus(SessionStatus.SCORED);
        verify(sessionRepo).save(existing);
    }

    @Test void upsertSession_sameStatus_ignoredIdempotent() {
        TrainingSession existing = mock(TrainingSession.class);
        when(existing.getStatus()).thenReturn(SessionStatus.SCORED);
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(existing));

        IngestAckResponse ack = ingest.upsertSession(SESSION, upsertReq(SessionStatus.SCORED));

        assertThat(ack.applied()).isFalse();
        assertThat(ack.duplicated()).isTrue();
        assertThat(ack.currentStatus()).isEqualTo(SessionStatus.SCORED);
        verify(existing, never()).setStatus(any());
        verify(sessionRepo, never()).save(any());
    }

    @Test void upsertSession_regression_ignored() {
        TrainingSession existing = mock(TrainingSession.class);
        when(existing.getStatus()).thenReturn(SessionStatus.SCORED);
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(existing));

        IngestAckResponse ack = ingest.upsertSession(SESSION, upsertReq(SessionStatus.RECORDED));   // 回退

        assertThat(ack.applied()).isFalse();
        assertThat(ack.duplicated()).isTrue();
        assertThat(ack.currentStatus()).isEqualTo(SessionStatus.SCORED);   // 保持较高状态
        verify(sessionRepo, never()).save(any());
    }

    // ================= ingestActionClips =================

    @Test void ingestActionClips_mixOfDuplicateAcceptDivRejected() {
        // item0 已存在→duplicated;item1 save ok→accepted;item2 save 抛 DIV→duplicated;item3 save 抛其它→rejected
        when(clipRepo.existsBySessionIdAndStudentIdAndClipIndex(SESSION, STU_A, 0)).thenReturn(true);
        when(clipRepo.existsBySessionIdAndStudentIdAndClipIndex(eq(SESSION), any(), eq(1))).thenReturn(false);
        when(clipRepo.existsBySessionIdAndStudentIdAndClipIndex(eq(SESSION), any(), eq(2))).thenReturn(false);
        when(clipRepo.existsBySessionIdAndStudentIdAndClipIndex(eq(SESSION), any(), eq(3))).thenReturn(false);
        when(clipRepo.save(any()))
                .thenAnswer(i -> i.getArgument(0))                              // item1 ok
                .thenThrow(new DataIntegrityViolationException("dup"))          // item2
                .thenThrow(new RuntimeException("bad json"));                   // item3

        ActionClipBatchRequest req = new ActionClipBatchRequest(SESSION, List.of(
                clip(STU_A, 0), clip(STU_A, 1), clip(STU_A, 2), clip(STU_A, 3)));

        BatchAckResponse ack = ingest.ingestActionClips(req);
        assertThat(ack.accepted()).isEqualTo(1);
        assertThat(ack.duplicated()).isEqualTo(2);   // 预检命中 1 + DIV 1
        assertThat(ack.rejected()).hasSize(1);
        assertThat(ack.rejected().get(0).eventId()).isEqualTo(STU_A + "#3");
    }

    @Test void ingestActionClips_derivesSessionAggregate_meanAnglesAndFgPct() {
        when(clipRepo.existsBySessionIdAndStudentIdAndClipIndex(any(), any(), anyInt())).thenReturn(false);
        when(clipRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // 派生阶段读回:两条 free_throw,右肘 150/170,一中一不中,同源 triangulated_3d
        ActionClip c0 = clipEntity(0, "free_throw", 150.0, true);
        ActionClip c1 = clipEntity(1, "free_throw", 170.0, false);
        when(clipRepo.findBySessionIdAndStudentIdOrderByClipIndex(SESSION, STU_A)).thenReturn(List.of(c0, c1));
        when(aggRepo.findBySessionIdAndStudentIdAndActionType(SESSION, STU_A, "free_throw"))
                .thenReturn(Optional.empty());
        ArgumentCaptor<SessionAggregate> cap = ArgumentCaptor.forClass(SessionAggregate.class);
        when(aggRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));

        ingest.ingestActionClips(new ActionClipBatchRequest(SESSION, List.of(
                clipItem(STU_A, 0, "free_throw"), clipItem(STU_A, 1, "free_throw"))));

        Map<String, Object> stats = cap.getValue().getStats();
        assertThat(stats.get("fg_pct")).isEqualTo(0.5);
        assertThat(stats.get("release_sample_count")).isEqualTo(2);
        assertThat(stats.get("angles_source")).isEqualTo("triangulated_3d");
        @SuppressWarnings("unchecked")
        Map<String, Object> mra = (Map<String, Object>) stats.get("mean_release_angles");
        assertThat(((Number) mra.get("right_elbow")).doubleValue()).isEqualTo(160.0);
    }

    // ================= ingestFeedback =================

    @Test void ingestFeedback_dedupByEventId_andDivDuplicated() {
        when(feedbackRepo.existsByEventId("e0")).thenReturn(true);          // 预检重复
        when(feedbackRepo.existsByEventId("e1")).thenReturn(false);
        when(feedbackRepo.existsByEventId("e2")).thenReturn(false);
        when(feedbackRepo.save(any()))
                .thenAnswer(i -> i.getArgument(0))                          // e1 ok
                .thenThrow(new DataIntegrityViolationException("dup"));     // e2

        InstantFeedbackBatchRequest req = new InstantFeedbackBatchRequest(SESSION, List.of(
                fb("e0"), fb("e1"), fb("e2")));

        BatchAckResponse ack = ingest.ingestFeedback(req);
        assertThat(ack.accepted()).isEqualTo(1);
        assertThat(ack.duplicated()).isEqualTo(2);
        assertThat(ack.rejected()).isEmpty();
    }

    @Test void ingestFeedback_genericError_rejectedWithEventId() {
        when(feedbackRepo.existsByEventId(any())).thenReturn(false);
        when(feedbackRepo.save(any())).thenThrow(new RuntimeException("boom"));

        BatchAckResponse ack = ingest.ingestFeedback(
                new InstantFeedbackBatchRequest(SESSION, List.of(fb("e9"))));
        assertThat(ack.accepted()).isZero();
        assertThat(ack.rejected()).extracting(RejectedItem::eventId).containsExactly("e9");
    }

    // ================= registerGallery =================

    @Test void registerGallery_supersedesOldActive_andDefaultsVersionTo1() {
        ReidGallery old = mock(ReidGallery.class);
        when(galleryRepo.findByStudentIdAndStatus(STU_A, GalleryStatus.ACTIVE)).thenReturn(Optional.of(old));
        when(galleryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        Student s = mock(Student.class);
        when(studentRepo.findById(STU_A)).thenReturn(Optional.of(s));

        RegisterGalleryRequest req = new RegisterGalleryRequest(
                STU_A, null, "s3://g", null, null, null, null, 10, null, null, null);

        GalleryRegisteredResponse r = ingest.registerGallery(req);
        assertThat(r.studentId()).isEqualTo(STU_A);
        assertThat(r.version()).isEqualTo(1);                 // null → 1
        verify(old).setStatus(GalleryStatus.SUPERSEDED);
        verify(galleryRepo).save(old);                        // 旧的换代落库
        verify(s).setActiveGalleryId(any());                  // student 指向新 gallery
    }

    @Test void registerGallery_noOldActive_stillRegisters() {
        when(galleryRepo.findByStudentIdAndStatus(STU_A, GalleryStatus.ACTIVE)).thenReturn(Optional.empty());
        when(galleryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(studentRepo.findById(STU_A)).thenReturn(Optional.empty());   // student 不存在也不炸

        GalleryRegisteredResponse r = ingest.registerGallery(new RegisterGalleryRequest(
                STU_A, 3, "s3://g", null, null, null, null, null, null, null, null));
        assertThat(r.version()).isEqualTo(3);
    }

    // ================= pullGallery =================

    @Test void pullGallery_nullLessonId_paramInvalid() {
        assertThatThrownBy(() -> ingest.pullGallery(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test void pullGallery_noEnrollment_emptyStudents() {
        when(enrollmentRepo.findStudentIdsByLessonId(LESSON)).thenReturn(List.of());
        GalleryPullResponse r = ingest.pullGallery(LESSON);
        assertThat(r.lessonId()).isEqualTo(LESSON);
        assertThat(r.students()).isEmpty();
    }

    @Test void pullGallery_mapsGalleryRefWhenPresent_nullWhenAbsent() {
        when(enrollmentRepo.findStudentIdsByLessonId(LESSON)).thenReturn(List.of(STU_A, STU_B));
        Student sa = mock(Student.class);
        Student sb = mock(Student.class);
        when(studentRepo.findById(STU_A)).thenReturn(Optional.of(sa));
        when(studentRepo.findById(STU_B)).thenReturn(Optional.of(sb));
        ReidGallery ga = mock(ReidGallery.class);
        when(ga.getStudentId()).thenReturn(STU_A);
        when(galleryRepo.findByStudentIdInAndStatus(any(), eq(GalleryStatus.ACTIVE))).thenReturn(List.of(ga));

        GalleryPullResponse r = ingest.pullGallery(LESSON);
        assertThat(r.students()).hasSize(2);
        assertThat(r.students().get(0).gallery()).isNotNull();   // STU_A 有 active gallery
        assertThat(r.students().get(1).gallery()).isNull();      // STU_B 无
    }

    @Test void pullGallery_skipsMissingStudent() {
        when(enrollmentRepo.findStudentIdsByLessonId(LESSON)).thenReturn(List.of(STU_A));
        when(studentRepo.findById(STU_A)).thenReturn(Optional.empty());   // 名单里但档案缺失
        when(galleryRepo.findByStudentIdInAndStatus(any(), any())).thenReturn(List.of());

        GalleryPullResponse r = ingest.pullGallery(LESSON);
        assertThat(r.students()).isEmpty();
    }

    // ================= helpers =================

    private UpsertSessionRequest upsertReq(SessionStatus status) {
        return new UpsertSessionRequest(LESSON, status, null, null, null, null, null, null, null);
    }

    private ActionClipBatchRequest.ClipItem clip(UUID studentId, int idx) {
        return new ActionClipBatchRequest.ClipItem(
                studentId, idx, "layup", BigDecimal.ZERO, BigDecimal.TEN,
                null, null, null, null, null, null, null, null);
    }

    private ActionClipBatchRequest.ClipItem clipItem(UUID studentId, int idx, String action) {
        return new ActionClipBatchRequest.ClipItem(
                studentId, idx, action, BigDecimal.ZERO, BigDecimal.TEN,
                null, null, null, null, null, null, null, null);
    }

    private ActionClip clipEntity(int idx, String action, double rightElbow, boolean made) {
        return ActionClip.builder()
                .sessionId(SESSION).studentId(STU_A).clipIndex(idx).actionType(action)
                .startMs(BigDecimal.ZERO).endMs(BigDecimal.TEN).shotMade(made)
                .score(Map.of("release_angles", Map.of("right_elbow", rightElbow),
                        "angles_source", "triangulated_3d"))
                .build();
    }

    private InstantFeedbackBatchRequest.Item fb(String eventId) {
        return new InstantFeedbackBatchRequest.Item(
                eventId, STU_A, OffsetDateTime.now(), null, "layup", "cp1",
                null, "抬肘", null, null, null);
    }
}
