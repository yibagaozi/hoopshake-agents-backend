package com.cnsportiot.edge.cv;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.ProcessState;
import com.cnsportiot.edge.process.ManagedProcess;
import com.cnsportiot.edge.process.ProcessSpec;
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
import java.util.List;
import java.util.Objects;

/** CV 进程托管 */
@Component
public class CvProcessManager {

    private static final Logger log = LoggerFactory.getLogger(CvProcessManager.class);

    private final EdgeProperties props;
    private final TaskExecutor supervisorExecutor;
    private ManagedProcess process;
    private volatile String currentSession;

    public CvProcessManager(EdgeProperties props,
                            @Qualifier("captureIoExecutor") TaskExecutor captureIoExecutor) {
        this.props = props;
        this.supervisorExecutor = captureIoExecutor;
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
                cfg.getMaxFailures()));
        currentSession = s;
        supervisorExecutor.execute(process);
        log.info("CV 进程启动中: {}", String.join(" ", cfg.getCommand()));
    }

    @PreDestroy
    public synchronized void stop() {
        if (process != null) {
            process.stop();
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

    public CvStatus status() {
        return new CvStatus(state(), currentSession);
    }

    /** CV 进程状态(供 /local/cv/status)。 */
    public record CvStatus(ProcessState state, String session) {}

    /** python 的输出需配合 PYTHONUNBUFFERED=1,否则块缓冲会让日志迟迟不出现 */
    private void drainToLog(InputStream in) {
        supervisorExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[cv] {}", line);
                }
            } catch (Exception ignored) {
                // 流关闭即进程退出,由 ManagedProcess 处理
            }
        });
    }
}

