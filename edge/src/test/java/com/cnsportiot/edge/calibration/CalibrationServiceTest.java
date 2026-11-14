package com.cnsportiot.edge.calibration;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.calibration.CalibrationService.CalibrationState;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.BatchProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 标定闸:就绪与否**只看产物文件**,不看跑没跑过。产物目录必须按课程隔离
 * (live_{课程id}),避免上一节课的外参污染这一节。
 */
class CalibrationServiceTest {

    private static final String LESSON = "0d7015b1-5f96-4400-b58a-160be4f0aa7f";

    private EdgeProperties props;
    private BatchProcessRunner runner;
    private CalibrationService svc;

    @BeforeEach
    void setup(@TempDir Path algoHome) {
        props = new EdgeProperties();
        EdgeProperties.Calibration c = props.getCalibration();
        c.setEnabled(true);
        c.setPythonExecutable("python");
        c.setScriptPath("scripts/run_live_ws.py");
        c.setWorkDir(algoHome.toString());
        c.setRequiredFiles(new java.util.ArrayList<>(List.of("cameras.json", "camera_centers_world.json")));
        c.setRequiredCameras(new java.util.ArrayList<>(List.of("cam_01", "cam_02", "cam_03")));
        runner = mock(BatchProcessRunner.class);
        // 同步执行器:测试里让异步任务就地跑完,断言才拿得到终态
        svc = new CalibrationService(props, runner, new SyncTaskExecutor());
    }

    private Path artifactDir() {
        return Path.of(props.getCalibration().getWorkDir(), "data", "calibration", "live_" + LESSON);
    }

    private void writeAllArtifacts() throws Exception {
        Path dir = artifactDir();
        Files.createDirectories(dir);
        for (String f : List.of("cameras.json", "camera_centers_world.json",
                "cam_01.json", "cam_02.json", "cam_03.json")) {
            Files.writeString(dir.resolve(f), "{}");
        }
    }

    // ---- 产物状态 ----

    @Test void status_noArtifacts_notReady_andListsAllMissing() {
        var st = svc.status(LESSON);
        assertThat(st.ready()).isFalse();
        assertThat(st.dirExists()).isFalse();
        assertThat(st.missingFiles())
                .containsExactlyInAnyOrder("cameras.json", "camera_centers_world.json",
                        "cam_01.json", "cam_02.json", "cam_03.json");
        assertThat(st.presentFiles()).isEmpty();
    }

    @Test void status_allArtifacts_ready() throws Exception {
        writeAllArtifacts();
        var st = svc.status(LESSON);
        assertThat(st.ready()).isTrue();
        assertThat(st.missingFiles()).isEmpty();
        assertThat(st.calibratedAt()).isNotNull();
    }

    /** 缺一个机位就不算就绪——三角化少一路就退化,不能放行。 */
    @Test void status_partialArtifacts_notReady() throws Exception {
        writeAllArtifacts();
        Files.delete(artifactDir().resolve("cam_03.json"));
        var st = svc.status(LESSON);
        assertThat(st.ready()).isFalse();
        assertThat(st.missingFiles()).containsExactly("cam_03.json");
    }

    /** 产物目录必须带课程 id,不同课互不影响。 */
    @Test void artifactDir_isPerLesson() throws Exception {
        writeAllArtifacts();
        assertThat(svc.status(LESSON).ready()).isTrue();
        assertThat(svc.status("another-lesson").ready()).isFalse();
        assertThat(svc.status(LESSON).artifactDir()).endsWith("live_" + LESSON);
    }

    // ---- 上课闸 ----

    @Test void ensureReady_whenCalibrated_returnsTrue_andDoesNotRunProcess() throws Exception {
        writeAllArtifacts();
        assertThat(svc.ensureReady(LESSON)).isTrue();
        verifyNoInteractions(runner);
    }

    /** 默认不自动拉起:标定要人工标注,后台拉只会弹个没人管的窗口。 */
    @Test void ensureReady_whenMissing_returnsFalse_andDoesNotAutoLaunchByDefault() {
        assertThat(props.getCalibration().isAutoOnStart()).isFalse();   // 默认关
        assertThat(svc.ensureReady(LESSON)).isFalse();
        verifyNoInteractions(runner);
    }

    /** 显式打开 auto-on-start 才会拉起(算法机前有人值守的场景)。 */
    @Test void ensureReady_autoOnStartEnabled_launchesCalibration() {
        props.getCalibration().setAutoOnStart(true);
        when(runner.run(any(), any(), any(), any())).thenReturn(0);
        assertThat(svc.ensureReady(LESSON)).isFalse();
        verify(runner).run(any(), any(), any(), any());
    }

    // ---- 触发标定 ----

    @Test void start_buildsCalibrateCommandWithLessonAsSession() {
        when(runner.run(any(), any(), any(), any())).thenReturn(0);
        svc.start(LESSON, true);

        @SuppressWarnings("unchecked")
        var cap = (org.mockito.ArgumentCaptor<List<String>>) (org.mockito.ArgumentCaptor<?>) forClass(List.class);
        verify(runner).run(cap.capture(), any(), any(), any());
        assertThat(cap.getValue()).containsSubsequence("scripts/run_live_ws.py", "calibrate", "--session", LESSON);
    }

    /** 退出码 0 但产物不齐 → 必须判失败,否则上课闸会误放行。 */
    @Test void start_exitZeroButArtifactsMissing_isFailed() {
        when(runner.run(any(), any(), any(), any())).thenReturn(0);
        svc.start(LESSON, true);
        var st = svc.status(LESSON);
        assertThat(st.ready()).isFalse();
        assertThat(st.lastRun().state()).isEqualTo(CalibrationState.FAILED);
        assertThat(st.lastRun().message()).contains("产物不完整");
    }

    @Test void start_exitZeroWithArtifacts_isSucceeded() throws Exception {
        writeAllArtifacts();
        when(runner.run(any(), any(), any(), any())).thenReturn(0);
        svc.start(LESSON, true);
        assertThat(svc.status(LESSON).lastRun().state()).isEqualTo(CalibrationState.SUCCEEDED);
    }

    @Test void start_nonZeroExit_isFailed() {
        when(runner.run(any(), any(), any(), any())).thenReturn(2);
        svc.start(LESSON, true);
        var run = svc.status(LESSON).lastRun();
        assertThat(run.state()).isEqualTo(CalibrationState.FAILED);
        assertThat(run.exitCode()).isEqualTo(2);
    }

    /** 已就绪且非强制:不重跑,免得把好产物覆盖掉。 */
    @Test void start_alreadyReadyWithoutForce_skipsProcess() throws Exception {
        writeAllArtifacts();
        var run = svc.start(LESSON, false);
        assertThat(run.state()).isEqualTo(CalibrationState.SUCCEEDED);
        verifyNoInteractions(runner);
    }

    @Test void start_disabled_throwsUnavailable() {
        props.getCalibration().setEnabled(false);
        assertThatThrownBy(() -> svc.start(LESSON, true)).isInstanceOf(BusinessException.class);
    }

    /** session 同时是目录名与命令行参数,必须挡住路径穿越。 */
    @Test void illegalSession_rejected() {
        assertThatThrownBy(() -> svc.status("../../etc")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.start("a b", true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.status(null)).isInstanceOf(BusinessException.class);
    }
}
