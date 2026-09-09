package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.ChatSession;
import com.cnsportiot.cloud.domain.entity.HelpRequest;
import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.CreateHelpRequest;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.HandleHelpRequest;
import com.cnsportiot.cloud.repository.ChatSessionRepository;
import com.cnsportiot.cloud.repository.HelpRequestRepository;
import com.cnsportiot.cloud.repository.HelpRequestRepository.HelpRequestView;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** HelpRequestServiceImpl 单测:身份闸、会话归属带出 lessonId、context 归一、教师筛选、处理归属/收尾 */
class HelpRequestServiceImplTest {

    private static final UUID STU = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID OTHER_STU = UUID.fromString("11111111-0000-0000-0000-000000000002");
    private static final UUID TEACHER = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID SESSION = UUID.fromString("33333333-0000-0000-0000-000000000001");
    private static final UUID LESSON = UUID.fromString("44444444-0000-0000-0000-000000000001");
    private static final UUID REQ = UUID.fromString("55555555-0000-0000-0000-000000000001");

    private HelpRequestRepository helpRepo;
    private ChatSessionRepository sessionRepo;
    private HelpRequestServiceImpl svc;

    @BeforeEach
    void setup() {
        helpRepo = mock(HelpRequestRepository.class);
        sessionRepo = mock(ChatSessionRepository.class);
        svc = new HelpRequestServiceImpl(helpRepo, sessionRepo);
        lenient().when(helpRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // create

    @Test void create_nullStudent_forbidden() {
        assertBusiness(() -> svc.create(null, new CreateHelpRequest(null, "q", null)), ErrorCode.FORBIDDEN);
    }

    @Test void create_ownedSession_carriesLessonId_andStripsQuestion() {
        ChatSession s = mock(ChatSession.class);
        when(s.getStudentId()).thenReturn(STU);
        when(s.getLessonId()).thenReturn(LESSON);
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(s));
        ArgumentCaptor<HelpRequest> cap = ArgumentCaptor.forClass(HelpRequest.class);
        when(helpRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));

        svc.create(STU, new CreateHelpRequest(SESSION, "  抬肘不稳  ", "  垫一下  "));
        HelpRequest hr = cap.getValue();
        assertThat(hr.getLessonId()).isEqualTo(LESSON);          // 从会话带出
        assertThat(hr.getQuestion()).isEqualTo("抬肘不稳");       // strip
        assertThat(hr.getStatus()).isEqualTo(HelpRequestStatus.PENDING);
        assertThat(hr.getContext()).containsEntry("note", "垫一下");
    }

    @Test void create_unownedSession_lessonIdNull() {
        ChatSession s = mock(ChatSession.class);
        when(s.getStudentId()).thenReturn(OTHER_STU);   // 别人的会话
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.of(s));
        ArgumentCaptor<HelpRequest> cap = ArgumentCaptor.forClass(HelpRequest.class);
        when(helpRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));

        svc.create(STU, new CreateHelpRequest(SESSION, "q", null));
        assertThat(cap.getValue().getLessonId()).isNull();       // 不越权带出他人会话的课程
    }

    @Test void create_absentSession_lessonIdNull_contextNullWhenBlankNote() {
        when(sessionRepo.findById(SESSION)).thenReturn(Optional.empty());
        ArgumentCaptor<HelpRequest> cap = ArgumentCaptor.forClass(HelpRequest.class);
        when(helpRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));

        svc.create(STU, new CreateHelpRequest(SESSION, "q", "   "));
        assertThat(cap.getValue().getLessonId()).isNull();
        assertThat(cap.getValue().getContext()).isNull();        // 空补充说明 → context 为 null
    }

    @Test void create_noSession_ok() {
        ArgumentCaptor<HelpRequest> cap = ArgumentCaptor.forClass(HelpRequest.class);
        when(helpRepo.save(cap.capture())).thenAnswer(i -> i.getArgument(0));
        svc.create(STU, new CreateHelpRequest(null, "q", null));
        assertThat(cap.getValue().getSessionId()).isNull();
        verifyNoInteractions(sessionRepo);
    }

    // list

    @Test void listForStudent_nullStudent_forbidden() {
        assertBusiness(() -> svc.listForStudent(null, 0, 20), ErrorCode.FORBIDDEN);
    }

    @Test void listForTeacher_noStatus_passesNullToMergedQuery() {
        when(helpRepo.findForTeacher(eq(TEACHER), isNull(), any())).thenReturn(Page.empty());
        svc.listForTeacher(TEACHER, null, 0, 20);
        verify(helpRepo).findForTeacher(eq(TEACHER), isNull(), any());   // status=null → 不过滤
    }

    @Test void listForTeacher_withStatus_passesStatusToMergedQuery() {
        when(helpRepo.findForTeacher(eq(TEACHER), eq(HelpRequestStatus.PENDING), any())).thenReturn(Page.empty());
        svc.listForTeacher(TEACHER, HelpRequestStatus.PENDING, 0, 20);
        verify(helpRepo).findForTeacher(eq(TEACHER), eq(HelpRequestStatus.PENDING), any());
    }

    // handle

    @Test void handle_notFound() {
        when(helpRepo.findById(REQ)).thenReturn(Optional.empty());
        assertBusiness(() -> svc.handle(TEACHER, REQ, new HandleHelpRequest(HelpRequestStatus.VIEWED, null)),
                ErrorCode.NOT_FOUND);
    }

    @Test void handle_studentNotUnderTeacher_dataScopeDenied() {
        HelpRequest hr = mock(HelpRequest.class);
        when(hr.getStudentId()).thenReturn(STU);
        when(helpRepo.findById(REQ)).thenReturn(Optional.of(hr));
        when(helpRepo.isStudentOfTeacher(TEACHER, STU)).thenReturn(false);
        assertBusiness(() -> svc.handle(TEACHER, REQ, new HandleHelpRequest(HelpRequestStatus.RESOLVED, "ok")),
                ErrorCode.DATA_SCOPE_DENIED);
    }

    @Test void handle_resolved_setsReplyAndHandledMarkers() {
        HelpRequest hr = mock(HelpRequest.class);
        when(hr.getStudentId()).thenReturn(STU);
        when(hr.getId()).thenReturn(REQ);
        when(helpRepo.findById(REQ)).thenReturn(Optional.of(hr));
        when(helpRepo.isStudentOfTeacher(TEACHER, STU)).thenReturn(true);
        when(helpRepo.findViewById(REQ)).thenReturn(Optional.of(mock(HelpRequestView.class)));

        svc.handle(TEACHER, REQ, new HandleHelpRequest(HelpRequestStatus.RESOLVED, "  多练脚步  "));
        verify(hr).setStatus(HelpRequestStatus.RESOLVED);
        verify(hr).setTeacherReply("多练脚步");               // strip
        verify(hr).setHandledBy(TEACHER);
        verify(hr).setHandledAt(any());
    }

    @Test void handle_viewed_blankReply_noHandledMarkers() {
        HelpRequest hr = mock(HelpRequest.class);
        when(hr.getStudentId()).thenReturn(STU);
        when(hr.getId()).thenReturn(REQ);
        when(helpRepo.findById(REQ)).thenReturn(Optional.of(hr));
        when(helpRepo.isStudentOfTeacher(TEACHER, STU)).thenReturn(true);
        when(helpRepo.findViewById(REQ)).thenReturn(Optional.of(mock(HelpRequestView.class)));

        svc.handle(TEACHER, REQ, new HandleHelpRequest(HelpRequestStatus.VIEWED, "  "));
        verify(hr).setStatus(HelpRequestStatus.VIEWED);
        verify(hr, never()).setTeacherReply(any());   // 空白答复不写
        verify(hr, never()).setHandledBy(any());      // VIEWED 非收尾态,不记处理人
        verify(hr, never()).setHandledAt(any());
    }

    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code));
    }
}

