package com.cnsportiot.edge.cv;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.ProcessState;
import com.cnsportiot.edge.process.ManagedProcess;
import com.cnsportiot.edge.process.ManagedProcessListener;
import com.cnsportiot.edge.process.ProcessSpec;
import com.cnsportiot.edge.telemetry.ProcessOutputTelemetry;
import com.cnsportiot.edge.telemetry.TelemetryCollector;
import com.cnsportiot.edge.telemetry.TelemetryRunEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** CV 进程托管 */
@Component
public class CvProcessManager {

    private static final Logger log = LoggerFactory.getLogger(CvProcessManager.class);

    private final EdgeProperties props;
    private final TaskExecutor supervisorExecutor;
    private final TelemetryCollector telemetry;
    private final ProcessOutputTelemetry outputTelemetry;
    private ManagedProcess process;
    private volatile String currentSession;
    private volatile List<String> currentCommand;
    private volatile String currentWorkDir;
    private volatile UUID runId;
    private volatile OffsetDateTime startedAt;
    private volatile boolean pendingAttempt;
    private final AtomicInteger restartCount = new AtomicInteger();

    public CvProcessManager(EdgeProperties props,
                            @Qualifier("captureIoExecutor") TaskExecutor captureIoExecutor,
                            TelemetryCollector telemetry,
                            ProcessOutputTelemetry outputTelemetry) {
        this.props = props;
        this.supervisorExecutor = captureIoExecutor;
        this.telemetry = telemetry;
        this.outputTelemetry = outputTelemetry;
    }

    /** 默认不自启;仅当 cv.enabled 且 cv.auto-start 时才在 Boot 拉起(用默认 session)。 */
    @EventListener(ApplicationReadyEvent.class)
    public void autoStart() {
        EdgeProperties.Cv cfg = props.getCv();
        if (cfg.isEnabled() && cfg.isAutoStart()) {
            start(null);
        }
    }

    /** 用默认 session 启动(手动按钮不带参数时)。 */
    public void start() {
        start(null);
    }

    public synchronized void start(String session) {
        EdgeProperties.Cv cfg = props.getCv();
        if (!cfg.isEnabled()) {
            log.info("CV 托管未启用,跳过");
            return;
        }
        if (cfg.getCommand() == null || cfg.getCommand().isEmpty()) {
            log.error("CV 命令行未配置(hoopshake.edge.cv.command),无法启动");
            return;
        }
        String s = (session != null && !session.isBlank()) ? session : cfg.getDefaultSession();

        if (process != null && process.alive()) {
            if (Objects.equals(currentSession, s)) {
                return;   // 同 session 已在跑,幂等
            }
            log.info("CV 切换 session {} → {},重启", currentSession, s);
            process.stop();
        } else {
            finishAttempt("STOPPED", null, Map.of("reason", "replaced"));
        }

        List<String> cmd = cfg.getCommand().stream()
                .map(a -> a == null ? null : a.replace("{session}", s == null ? "" : s))
                .toList();
        process = new ManagedProcess(new ProcessSpec(
                "cv",
                cmd,
                cfg.getWorkDir() == null || cfg.getWorkDir().isBlank()
                        ? null : new File(cfg.getWorkDir()),
                cfg.getEnv(),
                this::drainToLog,
                this::drainToLog,
                cfg.isAutoRestart(),
                cfg.getMaxFailures()),
                new CvProcessListener());
        currentSession = s;
        currentCommand = cmd;
        currentWorkDir = cfg.getWorkDir();
        runId = UUID.randomUUID();
        startedAt = OffsetDateTime.now();
        pendingAttempt = true;
        restartCount.set(0);
        telemetry.run(TelemetryRunEvent.builder()
                .runId(runId)
                .processType("cv")
                .processName("cv")
                .command(commandText(cmd))
                .workDir(cfg.getWorkDir())
                .startedAt(startedAt)
                .status("STARTING")
                .restartCount(0)
                .attrs(Map.of("session", s == null ? "" : s))
                .build());
        supervisorExecutor.execute(process);
        log.info("CV 进程启动中: {}", String.join(" ", cfg.getCommand()));
    }

    @PreDestroy
    public synchronized void stop() {
        if (process != null) {
            process.stop();
            finishAttempt("STOPPED", null, Map.of());
            log.info("CV 进程已停止");
        }
    }

    /** 以当前 session 重启(操作台“重启算法”)。 */
    public synchronized void restart() {
        String s = currentSession;
        stop();
        start(s);
    }

    public ProcessState state() {
        return process == null ? ProcessState.STOPPED : process.state();
    }

    public String currentSession() {
        return currentSession;
    }

    public Long pid() {
        return process == null ? null : process.pid();
    }

    public UUID currentRunId() {
        return runId;
    }

    public int restartCount() {
        return restartCount.get();
    }

    public CvStatus status() {
        return new CvStatus(state(), currentSession);
    }

    /** CV 进程状态(供 /local/cv/status)。 */
    public record CvStatus(ProcessState state, String session) {}

    /** python 的输出需配合 PYTHONUNBUFFERED=1,否则块缓冲会让日志迟迟不出现 */
    private void drainToLog(InputStream in) {
        supervisorExecutor.execute(() -> {
            UUID lineRunId = runId;
            ManagedProcess sourceProcess = process;
            Long pid = sourceProcess == null ? null : sourceProcess.pid();
            String session = currentSession;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[cv] {}", line);
                    outputTelemetry.line(line, "cv", "cv", lineRunId,
                            pid, session, "python");
                }
            } catch (Exception ignored) {
                // 流关闭即进程退出,由 ManagedProcess 处理
            }
        });
    }

    private synchronized void startAttempt(long pid) {
        UUID attemptRunId = runId;
        OffsetDateTime attemptStartedAt = startedAt;
        if (!pendingAttempt || attemptRunId == null || attemptStartedAt == null) {
            attemptRunId = UUID.randomUUID();
            attemptStartedAt = OffsetDateTime.now();
        }
        pendingAttempt = false;
        runId = attemptRunId;
        startedAt = attemptStartedAt;
        telemetry.run(TelemetryRunEvent.builder()
                .runId(attemptRunId)
                .processType("cv")
                .processName("cv")
                .command(commandText(currentCommand == null ? List.of() : currentCommand))
                .workDir(currentWorkDir)
                .startedAt(attemptStartedAt)
                .pid(pid)
                .status("RUNNING")
                .restartCount(restartCount.get())
                .attrs(Map.of("session", currentSession == null ? "" : currentSession))
                .build());
    }

    private synchronized void finishAttempt(String status, Integer exitCode,
                                            Map<String, Object> extraAttrs) {
        UUID currentRunId = runId;
        OffsetDateTime currentStartedAt = startedAt;
        if (currentRunId == null || currentStartedAt == null) {
            return;
        }
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("session", currentSession == null ? "" : currentSession);
        if (extraAttrs != null) {
            attrs.putAll(extraAttrs);
        }
        outputTelemetry.finish(currentRunId, "cv", "cv", "python");
        OffsetDateTime finishedAt = OffsetDateTime.now();
        telemetry.run(TelemetryRunEvent.builder()
                .runId(currentRunId)
                .processType("cv")
                .processName("cv")
                .startedAt(currentStartedAt)
                .finishedAt(finishedAt)
                .durationMs(Duration.between(currentStartedAt, finishedAt).toMillis())
                .pid(pid())
                .exitCode(exitCode)
                .status(status)
                .restartCount(restartCount.get())
                .attrs(attrs)
                .build());
        runId = null;
        startedAt = null;
        pendingAttempt = false;
    }

    private final class CvProcessListener implements ManagedProcessListener {
        @Override
        public void onStarted(String name, long pid) {
            startAttempt(pid);
        }

        @Override
        public void onExited(String name, long pid, int exitCode, boolean expected) {
            finishAttempt(expected ? "STOPPED" : "FAILED", exitCode,
                    Map.of("expectedExit", expected));
        }

        @Override
        public void onRestarting(String name, long pid, int exitCode, long delayMillis) {
            restartCount.incrementAndGet();
        }

        @Override
        public void onFailed(String name, Long pid, String reason) {
            finishAttempt("FAILED", null,
                    Map.of("failureReason", reason == null ? "" : reason));
        }
    }

    private static String commandText(List<String> command) {
        String text = String.join(" ", command);
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }
}
