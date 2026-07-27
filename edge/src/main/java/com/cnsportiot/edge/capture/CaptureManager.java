package com.cnsportiot.edge.capture;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.config.CameraRegistry;
import com.cnsportiot.edge.domain.CameraDescriptor;
import com.cnsportiot.edge.domain.CameraStatus;
import com.cnsportiot.edge.domain.enums.ProcessState;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.process.ManagedProcess;
import com.cnsportiot.edge.process.ProcessSpec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 四路采集进程的托管:每路一个 ffmpeg,常驻推流,与录制解耦 */
@Component
public class CaptureManager {

    private static final Logger log = LoggerFactory.getLogger(CaptureManager.class);

    private final CameraRegistry registry;
    private final FfmpegCommandBuilder commandBuilder;
    private final MediaMtxManager mediaMtx;
    private final TaskExecutor captureIoExecutor;

    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public CaptureManager(CameraRegistry registry,
                          FfmpegCommandBuilder commandBuilder,
                          MediaMtxManager mediaMtx,
                          TaskExecutor captureIoExecutor) {
        this.registry = registry;
        this.commandBuilder = commandBuilder;
        this.mediaMtx = mediaMtx;
        this.captureIoExecutor = captureIoExecutor;
    }

    /** 启动全部机位;必须先确保 mediamtx 就绪,否则 ffmpeg 推流会连不上 */
    public void startAll() {
        if (!mediaMtx.awaitReady()) {
            throw new BusinessException(EdgeErrorCode.MEDIAMTX_NOT_READY);
        }
        registry.all().forEach(cam -> start(cam.camId()));
    }

    public void start(String camId) {
        CameraDescriptor cam = registry.descriptor(camId);
        CameraStatus status = registry.status(camId);

        processes.compute(camId, (id, existing) -> {
            if (existing != null && existing.alive()) {
                return existing;
            }
            ManagedProcess p = new ManagedProcess(new ProcessSpec(
                    "capture-" + id,
                    commandBuilder.capture(cam),
                    null,
                    stdout -> captureIoExecutor.execute(new FfmpegProgressReader(stdout, status)),
                    stderr -> captureIoExecutor.execute(new BlackdetectReader(stderr, status)),
                    true, 5));
            captureIoExecutor.execute(p);
            status.captureState(ProcessState.STARTING);
            log.info("采集进程已提交: {}", id);
            return p;
        });
    }

    public void stop(String camId) {
        ManagedProcess p = processes.remove(camId);
        if (p != null) {
            p.stop();
            registry.status(camId).captureState(ProcessState.STOPPED);
        }
    }

    public void restart(String camId) {
        stop(camId);
        start(camId);
    }

    /** mediamtx 重启后调用:ffmpeg 的 RTSP 连接已断且不会自愈,必须全部重推 */
    public void restartAll() {
        registry.all().forEach(cam -> restart(cam.camId()));
    }

    public ProcessState stateOf(String camId) {
        ManagedProcess p = processes.get(camId);
        return p == null ? ProcessState.STOPPED : p.state();
    }

    public boolean anyRunning() {
        return processes.values().stream().anyMatch(ManagedProcess::alive);
    }

    @PreDestroy
    public void shutdown() {
        processes.values().forEach(ManagedProcess::stop);
        processes.clear();
    }
}

