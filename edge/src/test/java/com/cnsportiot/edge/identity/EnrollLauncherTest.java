package com.cnsportiot.edge.identity;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.BatchProcessRunner;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.identity.EnrollLauncher.EnrollRunStatus;
import com.cnsportiot.edge.identity.EnrollLauncher.EnrollState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * EnrollLauncher:命令拼装、默认值回填、退出码→状态映射、session 校验、未启用拦截、单飞闸(BUSY)+ 结束后释放;
 * 本地视频注册:--video 命令、导入目录约束、上传落盘与列表。
 * 同步执行器用于终态断言;单飞/RUNNING 用真单线程池 + 闩控进程。
 */
class EnrollLauncherTest {

    /** 记录调用参数、可配置退出码/异常/阻塞的假进程运行器。 */
    static final class FakeRunner implements BatchProcessRunner {
        volatile List<String> lastCommand;
        volatile File lastWorkDir;
        volatile Map<String, String> lastEnv;
        volatile Duration lastTimeout;
        int exitCode = 0;
        RuntimeException toThrow;
        CountDownLatch started;   // run() 进入时 countDown
        CountDownLatch gate;      // 非空则阻塞直到 countDown

        @Override
        public int run(List<String> command, File workDir, Map<String, String> env, Duration timeout) {
            this.lastCommand = command;
            this.lastWorkDir = workDir;
            this.lastEnv = env;
            this.lastTimeout = timeout;
            if (started != null) {
                started.countDown();
            }
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (toThrow != null) {
                throw toThrow;
            }
            return exitCode;
        }
    }

    private EdgeProperties enabledProps() {
        EdgeProperties p = new EdgeProperties();
        EdgeProperties.Enroll e = p.getEnroll();
        e.setEnabled(true);
        e.setPythonExecutable("python");
        e.setScriptPath("scripts/run_live_ws.py");
        e.getExtraArgs().add("--rtsp-json");
        e.getExtraArgs().add("rtsp.json");
        return p;
    }

    private static EdgeErrorCode edgeCode(Throwable t) {
        return (EdgeErrorCode) ((BusinessException) t).errorCode();
    }

    // ---- 前置校验 ----

    @Test
    void start_disabled_throwsUnavailable() {
        EnrollLauncher l = new EnrollLauncher(new EdgeProperties(), new FakeRunner(), new SyncTaskExecutor());
        assertThatThrownBy(() -> l.start("704b7877", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(edgeCode(e)).isEqualTo(EdgeErrorCode.ENROLL_UNAVAILABLE));
    }

    @Test
    void start_illegalSession_throwsParamInvalid() {
        EnrollLauncher l = new EnrollLauncher(enabledProps(), new FakeRunner(), new SyncTaskExecutor());
        assertThatThrownBy(() -> l.start("bad/../session", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    // ---- 命令拼装 ----

    @Test
    void start_buildsFullCommand_withOverrides() {
        FakeRunner runner = new FakeRunner();
        EnrollLauncher l = new EnrollLauncher(enabledProps(), runner, new SyncTaskExecutor());

        l.start("704b7877-5fd9-4686-ab09-2eb5ba08a0c4", "cam_01", 30.0, 6, 8);

        assertThat(runner.lastCommand).containsSequence(
                "python", "scripts/run_live_ws.py", "enroll",
                "--session", "704b7877-5fd9-4686-ab09-2eb5ba08a0c4",
                "--enroll-camera", "cam_01",
                "--seconds", "30.0",
                "--sample-hz", "6",
                "--expected-persons", "8");
        assertThat(runner.lastCommand).endsWith("--rtsp-json", "rtsp.json");
    }

    @Test
    void start_fillsDefaults_andOmitsExpectedWhenNull() {
        FakeRunner runner = new FakeRunner();
        EnrollLauncher l = new EnrollLauncher(enabledProps(), runner, new SyncTaskExecutor());

        l.start("lessonA", null, null, null, null);

        assertThat(runner.lastCommand).containsSequence(
                "--enroll-camera", "cam_03", "--seconds", "45.0", "--sample-hz", "8");
        assertThat(runner.lastCommand).doesNotContain("--expected-persons");
    }

    // ---- 退出码 → 状态 ----

    @Test
    void start_exitZero_marksSucceeded() {
        FakeRunner runner = new FakeRunner();
        EnrollLauncher l = new EnrollLauncher(enabledProps(), runner, new SyncTaskExecutor());

        l.start("lessonA", null, null, null, null);

        EnrollRunStatus s = l.status("lessonA");
        assertThat(s.state()).isEqualTo(EnrollState.SUCCEEDED);
        assertThat(s.exitCode()).isZero();
        assertThat(s.finishedAt()).isNotNull();
    }

    @Test
    void start_nonZeroExit_marksFailedWithCode() {
        FakeRunner runner = new FakeRunner();
        runner.exitCode = 3;
        EnrollLauncher l = new EnrollLauncher(enabledProps(), runner, new SyncTaskExecutor());

        l.start("lessonA", null, null, null, null);

        EnrollRunStatus s = l.status("lessonA");
        assertThat(s.state()).isEqualTo(EnrollState.FAILED);
        assertThat(s.exitCode()).isEqualTo(3);
    }

    @Test
    void start_runnerThrows_marksFailedNoCode() {
        FakeRunner runner = new FakeRunner();
        runner.toThrow = new IllegalStateException("boom");
        EnrollLauncher l = new EnrollLauncher(enabledProps(), runner, new SyncTaskExecutor());

        l.start("lessonA", null, null, null, null);

        EnrollRunStatus s = l.status("lessonA");
        assertThat(s.state()).isEqualTo(EnrollState.FAILED);
        assertThat(s.exitCode()).isNull();
        assertThat(s.message()).contains("boom");
    }

    @Test
    void status_neverRun_returnsNone() {
        EnrollLauncher l = new EnrollLauncher(enabledProps(), new FakeRunner(), new SyncTaskExecutor());
        assertThat(l.status("nope").state()).isEqualTo(EnrollState.NONE);
    }

    // ---- 单飞闸:运行中拒新任务,结束后释放 ----

    @Test
    void start_singleFlight_busyWhileRunning_thenFreesAfterDone() throws Exception {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(4);
        exec.initialize();

        FakeRunner runner = new FakeRunner();
        runner.started = new CountDownLatch(1);
        runner.gate = new CountDownLatch(1);
        EnrollLauncher l = new EnrollLauncher(enabledProps(), runner, exec);

        EnrollRunStatus first = l.start("s1", null, null, null, null);
        assertThat(first.state()).isEqualTo(EnrollState.RUNNING);
        assertThat(runner.started.await(2, TimeUnit.SECONDS)).isTrue();   // 进程已进入
        assertThat(l.status("s1").state()).isEqualTo(EnrollState.RUNNING);

        // 运行中,另一个 session 被单飞闸拒绝
        assertThatThrownBy(() -> l.start("s2", null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(edgeCode(e)).isEqualTo(EdgeErrorCode.ENROLL_BUSY));

        runner.gate.countDown();   // 放行,进程结束
        assertThat(waitUntil(() -> l.status("s1").state() == EnrollState.SUCCEEDED, 3000)).isTrue();

        // 闸已释放 → 可再次拉起(不再 BUSY)
        EnrollRunStatus third = l.start("s3", null, null, null, null);
        assertThat(third.state()).isEqualTo(EnrollState.RUNNING);

        exec.shutdown();
    }

    // ---- 本地视频注册 ----

    private EdgeProperties videoProps(Path videoDir) {
        EdgeProperties p = enabledProps();
        p.getEnroll().setVideoDir(videoDir.toString());
        return p;
    }

    private static Path video(Path videoDir, String rel) throws Exception {
        Path f = videoDir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "fake video");
        return f;
    }

    @Test
    void start_video_passesVideoAndNoPreview_wholeVideoByDefault(@TempDir Path dir) throws Exception {
        Path f = video(dir, "lessonA/enroll.mp4");
        FakeRunner runner = new FakeRunner();
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), runner, new SyncTaskExecutor());

        EnrollRunStatus s = l.start("lessonA", null, null, null, 30, "lessonA/enroll.mp4");

        assertThat(s.message()).isEqualTo("视频注册中");
        assertThat(runner.lastCommand).containsSequence(
                "--enroll-camera", "cam_03", "--video", f.toAbsolutePath().normalize().toString(), "--no-preview",
                "--sample-hz", "8", "--expected-persons", "30");
        assertThat(runner.lastCommand).doesNotContain("--seconds");
        assertThat(l.status("lessonA").state()).isEqualTo(EnrollState.SUCCEEDED);
    }

    @Test
    void start_video_withSeconds_limitsToFirstSeconds(@TempDir Path dir) throws Exception {
        video(dir, "a.mkv");
        FakeRunner runner = new FakeRunner();
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), runner, new SyncTaskExecutor());

        l.start("lessonA", null, 90.0, null, null, "a.mkv");

        assertThat(runner.lastCommand).containsSequence("--no-preview", "--seconds", "90.0");
    }

    @Test
    void start_video_outsideImportDir_rejected(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        Path outside = video(elsewhere, "secret.mp4");
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), new FakeRunner(), new SyncTaskExecutor());

        for (String v : List.of("../secret.mp4", outside.toString())) {
            assertThatThrownBy(() -> l.start("lessonA", null, null, null, null, v))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        }
        assertThat(l.status("lessonA").state()).isEqualTo(EnrollState.NONE);
    }

    @Test
    void start_video_badExtensionOrMissing_rejected(@TempDir Path dir) throws Exception {
        video(dir, "notes.txt");
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), new FakeRunner(), new SyncTaskExecutor());

        assertThatThrownBy(() -> l.start("lessonA", null, null, null, null, "notes.txt"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> l.start("lessonA", null, null, null, null, "missing.mp4"))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void start_video_withoutVideoDir_unavailable() {
        EnrollLauncher l = new EnrollLauncher(enabledProps(), new FakeRunner(), new SyncTaskExecutor());
        assertThatThrownBy(() -> l.start("lessonA", null, null, null, null, "a.mp4"))
                .satisfies(e -> assertThat(edgeCode(e)).isEqualTo(EdgeErrorCode.ENROLL_UNAVAILABLE));
    }

    @Test
    void importVideo_savesUnderSession_withSanitizedName(@TempDir Path dir) throws Exception {
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), new FakeRunner(), new SyncTaskExecutor());

        String rel = l.importVideo("lessonA", "C:\\Users\\t\\enroll (1)#.MP4",
                new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        assertThat(rel).isEqualTo("lessonA/enroll _1__.MP4");
        assertThat(Files.readString(dir.resolve(rel))).isEqualTo("bytes");
        try (var files = Files.list(dir.resolve("lessonA"))) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("enroll _1__.MP4");
        }
        assertThat(l.listVideos()).singleElement()
                .satisfies(v -> {
                    assertThat(v.video()).isEqualTo(rel);
                    assertThat(v.sizeBytes()).isEqualTo(5);
                });
    }

    @Test
    void importVideo_keepsChineseName_whereFileSystemCanEncodeIt(@TempDir Path dir) {
        assumeTrue(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")).newEncoder().canEncode("注册"),
                "文件系统编码不支持中文名(Windows 恒支持)");
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), new FakeRunner(), new SyncTaskExecutor());

        String rel = l.importVideo("lessonA", "注册 录像(1).mp4", new ByteArrayInputStream(new byte[]{1}));

        assertThat(rel).isEqualTo("lessonA/注册 录像_1_.mp4");
        assertThat(dir.resolve(rel)).exists();
    }

    @Test
    void importVideo_rejectsNonVideoAndBadSession(@TempDir Path dir) {
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), new FakeRunner(), new SyncTaskExecutor());
        var in = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(() -> l.importVideo("lessonA", "payload.exe", in))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> l.importVideo("../x", "a.mp4", in))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        assertThat(l.listVideos()).isEmpty();
    }

    @Test
    void importVideo_busy_rejectedBeforeSaving(@TempDir Path dir) throws Exception {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.initialize();
        FakeRunner runner = new FakeRunner();
        runner.started = new CountDownLatch(1);
        runner.gate = new CountDownLatch(1);
        EnrollLauncher l = new EnrollLauncher(videoProps(dir), runner, exec);
        l.start("s1", null, null, null, null);
        assertThat(runner.started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> l.importVideo("s2", "a.mp4", new ByteArrayInputStream(new byte[]{1})))
                .satisfies(e -> assertThat(edgeCode(e)).isEqualTo(EdgeErrorCode.ENROLL_BUSY));
        assertThat(dir.resolve("s2")).doesNotExist();

        runner.gate.countDown();
        exec.shutdown();
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }
}
