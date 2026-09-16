package com.cnsportiot.edge.cv;

import com.cnsportiot.edge.telemetry.ProcessOutputTelemetry;
import com.cnsportiot.edge.telemetry.TelemetryCollector;
import com.cnsportiot.edge.telemetry.TelemetryRunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link BatchProcessRunner} 的默认实现:{@link ProcessBuilder} 起子进程,合并 stderr 到 stdout,
 * 用守护线程把输出抽到日志(前缀 {@code [batch]}),主线程 {@code waitFor} 拿退出码。
 * 超时则强杀并抛异常(由编排器判定不出云)。同时记录进程运行数据与 Python 输出遥测。
 */
@Component
public class DefaultBatchProcessRunner implements BatchProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultBatchProcessRunner.class);

    private final TelemetryCollector telemetry;
    private final ProcessOutputTelemetry outputTelemetry;

    public DefaultBatchProcessRunner(TelemetryCollector telemetry,
                                     ProcessOutputTelemetry outputTelemetry) {
        this.telemetry = telemetry;
        this.outputTelemetry = outputTelemetry;
    }

    @Override
    public int run(List<String> command, File workDir, Map<String, String> env, Duration timeout) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.now();
        String processName = command.isEmpty() ? "batch" : new File(command.get(0)).getName();
        String source = command.isEmpty() || !command.get(0).toLowerCase().contains("python")
                ? "process" : "python";
        Process process = null;
        Integer exitCode = null;
        String status = "RUNNING";
        try {
            try {
                process = start(command, workDir, env, runId, startedAt, processName, timeout);
            } catch (RuntimeException e) {
                status = "START_FAILED";
                throw e;
            }
            startDrain(process, runId, processName, source);

            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    status = "TIMEOUT";
                    throw new IllegalStateException("批处理超时(" + timeout + "),已强制结束");
                }
            } else {
                process.waitFor();
            }
            exitCode = process.exitValue();
            status = exitCode == 0 ? "SUCCEEDED" : "FAILED";
            return exitCode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            status = "INTERRUPTED";
            throw new IllegalStateException("批处理被中断", e);
        } finally {
            outputTelemetry.finish(runId, "batch", processName, source);
            OffsetDateTime finishedAt = OffsetDateTime.now();
            telemetry.run(TelemetryRunEvent.builder()
                    .runId(runId)
                    .processType("batch")
                    .processName(processName)
                    .command(commandText(command))
                    .workDir(workDir == null ? null : workDir.getAbsolutePath())
                    .startedAt(startedAt)
                    .finishedAt(finishedAt)
                    .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                    .pid(process == null ? null : process.pid())
                    .exitCode(exitCode)
                    .status(status)
                    .timeoutMs(timeout == null ? null : timeout.toMillis())
                    .attrs(Map.of("source", source))
                    .build());
        }
    }

    private Process start(List<String> command, File workDir, Map<String, String> env,
                          UUID runId, OffsetDateTime startedAt, String processName, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir);
        }
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("批处理进程启动失败: " + e.getMessage(), e);
        }
        telemetry.run(TelemetryRunEvent.builder()
                .runId(runId)
                .processType("batch")
                .processName(processName)
                .command(commandText(command))
                .workDir(workDir == null ? null : workDir.getAbsolutePath())
                .startedAt(startedAt)
                .pid(process.pid())
                .status("RUNNING")
                .timeoutMs(timeout == null ? null : timeout.toMillis())
                .build());
        return process;
    }

    private void startDrain(Process process, UUID runId, String processName, String source) {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[batch] {}", line);
                    outputTelemetry.line(line, "batch", processName, runId,
                            process.pid(), null, source);
                }
            } catch (IOException ignored) {
                // 流关闭即进程退出
            }
        }, "batch-drain-" + runId);
        drain.setDaemon(true);
        drain.start();
    }

    private static String commandText(List<String> command) {
        String text = String.join(" ", command);
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }
}
