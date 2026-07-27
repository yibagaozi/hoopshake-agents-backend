package com.cnsportiot.edge.capture;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.ProcessState;
import com.cnsportiot.edge.process.ManagedProcess;
import com.cnsportiot.edge.process.ProcessSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;

/** 本地 mediamtx 进程托管 */
@Component
public class MediaMtxManager {

    private static final Logger log = LoggerFactory.getLogger(MediaMtxManager.class);

    private final EdgeProperties props;
    private final TaskExecutor supervisorExecutor;
    private ManagedProcess process;

    public MediaMtxManager(EdgeProperties props, TaskExecutor captureIoExecutor) {
        this.props = props;
        this.supervisorExecutor = captureIoExecutor;
    }

    public synchronized void start() {
        if (process != null && process.alive()) {
            return;
        }
        EdgeProperties.MediaMtx cfg = props.getMediamtx();
        List<String> cmd = cfg.getConfig() == null
                ? List.of(cfg.getExecutable())
                : List.of(cfg.getExecutable(), cfg.getConfig());

        process = new ManagedProcess(new ProcessSpec(
                "mediamtx", cmd, workDir(cfg),
                this::drainToLog, this::drainToLog,
                true, 5));
        supervisorExecutor.execute(process);
    }

    public synchronized void stop() {
        if (process != null) {
            process.stop();
        }
    }

    /** 阻塞等待 RTSP 端口可连接;ffmpeg 起太早会连不上直接退出 */
    public boolean awaitReady() {
        EdgeProperties.MediaMtx cfg = props.getMediamtx();
        URI uri = URI.create(cfg.getRtspBase());
        long deadline = System.currentTimeMillis() + cfg.getReadyTimeoutMillis();
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8554)) {
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
        log.error("mediamtx 未在 {}ms 内就绪", cfg.getReadyTimeoutMillis());
        return false;
    }

    public ProcessState state() {
        return process == null ? ProcessState.STOPPED : process.state();
    }

    private static boolean portOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private File workDir(EdgeProperties.MediaMtx cfg) {
        File exe = new File(cfg.getExecutable());
        return exe.getParentFile();
    }

    private void drainToLog(InputStream in) {
        supervisorExecutor.execute(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[mediamtx] {}", line);
                }
            } catch (Exception ignored) {
                // 流关闭即进程退出,由 ManagedProcess 处理
            }
        });
    }
}

