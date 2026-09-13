package com.cnsportiot.edge.service.impl;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.camera.CameraRegistry;
import com.cnsportiot.edge.capture.CaptureManager;
import com.cnsportiot.edge.capture.RecordingManager;
import com.cnsportiot.edge.cloudsync.SessionBatchOrchestrator;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.CvProcessManager;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.SessionDtos.StartSessionRequest;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.service.LessonContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionServiceImpl:状态机 + CV 生命周期。重点回归:上课按 lessonId(=课程id)拉起 CV(算法据此加载注册库),
 * 下课停 CV;无课程的纯录制传 null(用默认 session)。
 */
class SessionServiceImplTest {

    private static final UUID LESSON = UUID.fromString("22222222-0000-0000-0000-000000000001");

    private EdgeProperties props;
    private CameraRegistry registry;
    private CaptureManager captureManager;
    private RecordingManager recordingManager;
    private LessonContextService lessonContextService;
    private SessionBatchOrchestrator batchOrchestrator;
    private CvProcessManager cvProcessManager;
    private SessionServiceImpl svc;

    @BeforeEach
    void setup(@TempDir Path root) {
        props = new EdgeProperties();
        props.setDataRoot(root.toString());
        registry = mock(CameraRegistry.class);
        captureManager = mock(CaptureManager.class);
        recordingManager = mock(RecordingManager.class);
        lessonContextService = mock(LessonContextService.class);
        batchOrchestrator = mock(SessionBatchOrchestrator.class);
        cvProcessManager = mock(CvProcessManager.class);

        when(captureManager.anyRunning()).thenReturn(true);
        when(registry.onlineCamIds()).thenReturn(List.of("cam_01"));
        when(registry.offlineCamIds()).thenReturn(List.of());
        when(registry.all()).thenReturn(List.of());
        when(recordingManager.startSegment(any(), any())).thenReturn(List.of());
        when(recordingManager.aliveByCam()).thenReturn(Map.of());
        when(lessonContextService.context()).thenReturn(Optional.empty());

        svc = new SessionServiceImpl(props, registry, captureManager, recordingManager,
                lessonContextService, JsonMapper.builder().build(), batchOrchestrator, cvProcessManager);
    }

    private StartSessionRequest req() {
        return new StartSessionRequest(LESSON);
    }

    // ---- CV 生命周期 ----

    @Test void start_withLesson_launchesCvWithLessonSession() {
        svc.start(req());
        verify(cvProcessManager).start(LESSON.toString());   // CV 用课程id 作 session,对上注册库
    }

    @Test void start_noLesson_launchesCvWithNullSession() {
        when(lessonContextService.context()).thenReturn(Optional.empty());
        svc.start(new StartSessionRequest(null));            // 纯录制,无课程
        verify(cvProcessManager).start((String) null);        // → CV 用默认 session
    }

    @Test void stop_stopsCv() {
        svc.start(req());
        svc.stop();
        verify(cvProcessManager).stop();
    }

    @Test void start_captureNotRunning_doesNotLaunchCv() {
        when(captureManager.anyRunning()).thenReturn(false);
        assertBusiness(() -> svc.start(req()), EdgeErrorCode.FFMPEG_UNAVAILABLE);
        verify(cvProcessManager, never()).start(any());
    }

    // ---- 状态机 ----

    @Test void start_happyPath_recording() {
        SessionResponse r = svc.start(req());
        assertThat(r.state()).isEqualTo(SessionState.RECORDING);
        assertThat(r.lessonId()).isEqualTo(LESSON);
    }

    @Test void start_whenAlreadyRecording_stateConflict() {
        svc.start(req());
        assertBusiness(() -> svc.start(req()), ErrorCode.STATE_CONFLICT);
    }

    @Test void stop_whenNoSession_stateConflict() {
        assertBusiness(() -> svc.stop(), ErrorCode.STATE_CONFLICT);
    }

    @Test void fullCycle_start_pause_resume_stop() {
        assertThat(svc.start(req()).state()).isEqualTo(SessionState.RECORDING);
        assertThat(svc.pause().state()).isEqualTo(SessionState.PAUSED);
        assertThat(svc.resume().state()).isEqualTo(SessionState.RECORDING);
        assertThat(svc.stop().state()).isEqualTo(SessionState.ENDED);
        verify(cvProcessManager).stop();
    }

    @Test void current_noSessionNoLesson_idle() {
        assertThat(svc.current().state()).isEqualTo(SessionState.IDLE);
    }

    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code));
    }

    private static void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, EdgeErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(code));
    }
}
