package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.BatchProcessRunner;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 下课 → 批处理 → sessionProcessed 编排。用同步执行器让批处理内联跑,
 * 假 {@link BatchProcessRunner} 模拟退出码与交接文件产出,验证发信号选路与守卫。
 */
class SessionBatchOrchestratorTest {

    private static final UUID SID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private EdgeProperties enabledProps(Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        p.getBatch().setEnabled(true);
        p.getBatch().setCommand(List.of("python", "run_session.py",
                "--session", "{sessionId}", "--raw", "{rawDir}", "--cloud", "{cloudDir}"));
        return p;
    }

    private SessionBatchOrchestrator orch(EdgeProperties p, EdgeEventPublisher events, BatchProcessRunner runner) {
        return new SessionBatchOrchestrator(p, events, runner, new SyncTaskExecutor());
    }

    private Path sessionDir(Path root) {
        return root.resolve("sessions").resolve(SID.toString());
    }

    /** 让假 runner 返回给定退出码,并可选择写出交接文件(模拟算法产出)。 */
    private void stubRunner(BatchProcessRunner runner, int exit, Path dir, boolean writeHandoff) {
        when(runner.run(anyList(), any(), anyMap(), any())).thenAnswer(inv -> {
            if (writeHandoff) {
                Files.createDirectories(dir.resolve("cloud"));
                Files.writeString(dir.resolve("cloud/ingest.json"), "{\"session\":{}}");
            }
            return exit;
        });
    }

    // ---- onSessionEnded(下课自动) ----

    @Test
    void onSessionEnded_disabled_noRunNoEmit(@TempDir Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());   // batch.enabled 默认 false
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);

        orch(p, events, runner).onSessionEnded(SID, sessionDir(root));

        verifyNoInteractions(runner, events);
    }

    @Test
    void onSessionEnded_enabledButNoCommand_noRunNoEmit(@TempDir Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        p.getBatch().setEnabled(true);   // 命令为空
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);

        orch(p, events, runner).onSessionEnded(SID, sessionDir(root));

        verifyNoInteractions(runner, events);
    }

    @Test
    void onSessionEnded_autoOnStopFalse_noRun(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);
        p.getBatch().setAutoOnStop(false);
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);

        orch(p, events, runner).onSessionEnded(SID, sessionDir(root));

        verifyNoInteractions(runner, events);
    }

    @Test
    void onSessionEnded_success_emitsSessionProcessed(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);
        Path dir = sessionDir(root);
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);
        stubRunner(runner, 0, dir, true);

        orch(p, events, runner).onSessionEnded(SID, dir);

        // 命令占位符已替换
        ArgumentCaptor<List<String>> cmdCap = captor();
        verify(runner).run(cmdCap.capture(), any(), anyMap(), any());
        assertThat(cmdCap.getValue()).contains(SID.toString())
                .anyMatch(a -> a.endsWith(SID + "/raw"))
                .anyMatch(a -> a.endsWith(SID + "/cloud"));

        // 发出 sessionProcessed,payload 带 sessionId + source=batch
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(WsEventType.SESSION_PROCESSED), payloadCap.capture());
        assertThat(payloadCap.getValue()).isInstanceOfSatisfying(WsEvents.SessionProcessed.class, sp -> {
            assertThat(sp.sessionId()).isEqualTo(SID);
            assertThat(sp.source()).isEqualTo("batch");
        });
    }

    @Test
    void onSessionEnded_nonZeroExit_noEmit(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);
        Path dir = sessionDir(root);
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);
        stubRunner(runner, 3, dir, true);   // 有交接文件但退出码非 0

        orch(p, events, runner).onSessionEnded(SID, dir);

        verify(runner).run(anyList(), any(), anyMap(), any());
        verify(events, never()).publish(any(), any());
    }

    @Test
    void onSessionEnded_zeroExitButNoHandoff_noEmit(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);
        Path dir = sessionDir(root);
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);
        stubRunner(runner, 0, dir, false);  // 退出 0 但没产出交接文件

        orch(p, events, runner).onSessionEnded(SID, dir);

        verify(events, never()).publish(any(), any());
    }

    @Test
    void onSessionEnded_runnerThrows_swallowedNoEmit(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);
        Path dir = sessionDir(root);
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);
        when(runner.run(anyList(), any(), anyMap(), any()))
                .thenThrow(new IllegalStateException("批处理超时"));

        orch(p, events, runner).onSessionEnded(SID, dir);   // 不应抛出

        verify(events, never()).publish(any(), any());
    }

    // ---- triggerProcess(手动跑批处理) ----

    @Test
    void triggerProcess_disabled_throwsBatchUnavailable(@TempDir Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        SessionBatchOrchestrator o = orch(p, mock(EdgeEventPublisher.class), mock(BatchProcessRunner.class));

        assertThatThrownBy(() -> o.triggerProcess(SID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(EdgeErrorCode.BATCH_UNAVAILABLE));
    }

    @Test
    void triggerProcess_dirMissing_throwsNotFound(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);   // 命令已配,但会话目录不存在
        SessionBatchOrchestrator o = orch(p, mock(EdgeEventPublisher.class), mock(BatchProcessRunner.class));

        assertThatThrownBy(() -> o.triggerProcess(SID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void triggerProcess_happy_runsAndEmits(@TempDir Path root) throws Exception {
        EdgeProperties p = enabledProps(root);
        Path dir = sessionDir(root);
        Files.createDirectories(dir);   // 目录存在
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);
        stubRunner(runner, 0, dir, true);

        orch(p, events, runner).triggerProcess(SID);

        verify(runner).run(anyList(), any(), anyMap(), any());
        verify(events).publish(eq(WsEventType.SESSION_PROCESSED), any());
    }

    // ---- triggerPublish(手动直接出云,不跑批处理) ----

    @Test
    void triggerPublish_handoffMissing_throws(@TempDir Path root) {
        EdgeProperties p = enabledProps(root);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);
        SessionBatchOrchestrator o = orch(p, mock(EdgeEventPublisher.class), runner);

        assertThatThrownBy(() -> o.triggerPublish(SID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(EdgeErrorCode.HANDOFF_MISSING));
        verifyNoInteractions(runner);
    }

    @Test
    void triggerPublish_handoffPresent_emitsWithoutRunningBatch(@TempDir Path root) throws Exception {
        EdgeProperties p = enabledProps(root);
        Path dir = sessionDir(root);
        Files.createDirectories(dir.resolve("cloud"));
        Files.writeString(dir.resolve("cloud/ingest.json"), "{\"session\":{}}");
        EdgeEventPublisher events = mock(EdgeEventPublisher.class);
        BatchProcessRunner runner = mock(BatchProcessRunner.class);

        orch(p, events, runner).triggerPublish(SID);

        verifyNoInteractions(runner);   // 不跑批处理
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(WsEventType.SESSION_PROCESSED), payloadCap.capture());
        assertThat(payloadCap.getValue()).isInstanceOfSatisfying(WsEvents.SessionProcessed.class,
                sp -> assertThat(sp.source()).isEqualTo("manual-publish"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<String>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
