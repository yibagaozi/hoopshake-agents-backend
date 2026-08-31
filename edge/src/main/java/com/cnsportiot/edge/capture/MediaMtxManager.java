package com.cnsportiot.edge.capture;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.ProcessState;
import com.cnsportiot.edge.process.ManagedProcess;
import com.cnsportiot.edge.process.ProcessSpec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 本地 mediamtx 进程托管 */
@Component
public class MediaMtxManager {

    private static final Logger log = LoggerFactory.getLogger(MediaMtxManager.class);

    private final EdgeProperties props;
    private final TaskExecutor supervisorExecutor;
    private ManagedProcess process;
    private volatile boolean externalInstance;

    public MediaMtxManager(EdgeProperties props,
                           @Qualifier("captureIoExecutor") TaskExecutor captureIoExecutor) {
        this.props = props;
        this.supervisorExecutor = captureIoExecutor;
    }

    public synchronized void start() {
        if (process != null && process.alive()) {
            return;
        }
        EdgeProperties.MediaMtx cfg = props.getMediamtx();

        if (portOpen(rtspPort(cfg))) {
            externalInstance = true;
            log.warn("RTSP 端口已被占用,复用已有 mediamtx 实例,跳过启动。"
                    + "若非预期,请清理残留进程(taskkill /F /IM mediamtx.exe)后重启");
            return;
        }
        externalInstance = false;

        List<String> cmd = (cfg.getConfig() == null || cfg.getConfig().isBlank())
                ? List.of(cfg.getExecutable())
                : List.of(cfg.getExecutable(), cfg.getConfig());

        process = new ManagedProcess(new ProcessSpec(
                "mediamtx", cmd, workDir(cfg), cfg.getEnv(),
                this::drainToLog, this::drainToLog,
                true, 5));
        supervisorExecutor.execute(process);
        log.info("mediamtx 启动中: {}", String.join(" ", cmd));
    }

    /** 应用关闭时停掉自己拉起的实例;复用的外部实例不动 */
    @PreDestroy
    public synchronized void stop() {
        if (externalInstance) {
            log.info("mediamtx 为复用的外部实例,不做停止");
            return;
        }
        if (process != null) {
            process.stop();
            log.info("mediamtx 已停止");
        }
    }

    /** 阻塞等待 RTSP 端口可连接;ffmpeg 起太早会连不上直接退出 */
    public boolean awaitReady() {
        EdgeProperties.MediaMtx cfg = props.getMediamtx();
        long deadline = System.currentTimeMillis() + cfg.getReadyTimeoutMillis();
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(rtspPort(cfg))) {
                log.info("mediamtx 已就绪: {}", cfg.getRtspBase());
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.error("mediamtx 未在 {}ms 内就绪,检查上方 [mediamtx] 日志", cfg.getReadyTimeoutMillis());
        return false;
    }

    public ProcessState state() {
        if (externalInstance) {
            return ProcessState.RUNNING;
        }
        return process == null ? ProcessState.STOPPED : process.state();
    }

    private InetSocketAddress rtspPort(EdgeProperties.MediaMtx cfg) {
        URI uri = URI.create(cfg.getRtspBase());
        return new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8554);
    }

    private static boolean portOpen(InetSocketAddress addr) {
        try (Socket s = new Socket()) {
            s.connect(addr, 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private File workDir(EdgeProperties.MediaMtx cfg) {
        return new File(cfg.getExecutable()).getParentFile();
    }

    /**
     * mediamtx 的输出转到 INFO —— 原来打在 DEBUG,
     * 默认日志档下「address already in use」这类退出原因完全看不到
     */
    private void drainToLog(InputStream in) {
        supervisorExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[mediamtx] {}", line);
                }
            } catch (Exception ignored) {
                // 流关闭即进程退出,由 ManagedProcess 处理
            }
        });
    }
}
