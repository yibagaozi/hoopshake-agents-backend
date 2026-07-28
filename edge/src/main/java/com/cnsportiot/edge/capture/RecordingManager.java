package com.cnsportiot.edge.capture;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.camera.CameraRegistry;
import com.cnsportiot.edge.domain.RecordingSegment;
import com.cnsportiot.edge.process.ManagedProcess;
import com.cnsportiot.edge.process.ProcessSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 录制进程托管:每路一个 ffmpeg,从本地 RTSP 拉流 {@code -c copy} 落盘 */
@Component
public class RecordingManager {

    private static final Logger log = LoggerFactory.getLogger(RecordingManager.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CameraRegistry registry;
    private final FfmpegCommandBuilder commandBuilder;
    private final TaskExecutor captureIoExecutor;

    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public RecordingManager(CameraRegistry registry,
                            FfmpegCommandBuilder commandBuilder,
                            TaskExecutor captureIoExecutor) {
        this.registry = registry;
        this.commandBuilder = commandBuilder;
        this.captureIoExecutor = captureIoExecutor;
    }

    /** 启动指定机位的录制,产出一段(每路一个文件) */
    public synchronized List<RecordingSegment> startSegment(Path dataDir, List<String> camIds) {
        if (recording()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "录制进程未清空");
        }
        processes.clear();

        OffsetDateTime now = OffsetDateTime.now();
        String ts = LocalDateTime.now().format(FILE_TS);
        List<RecordingSegment> segments = new ArrayList<>();

        for (String camId : camIds) {
            registry.descriptor(camId);   // 校验机位存在
            String fileName = ts + "_" + camId + ".mkv";
            Path file = dataDir.resolve(fileName);

            ManagedProcess p = new ManagedProcess(new ProcessSpec(
                    "record-" + camId,
                    commandBuilder.record(camId, file.toString()),
                    null,
                    null,
                    null,
                    stderr -> captureIoExecutor.execute(() -> drain(camId, stderr)),
                    // 中途崩溃若自动重启会覆盖同名文件,故不自动重启,
                    // 由 /local/session/health 暴露给操作台人工处理
                    false, 1));
            captureIoExecutor.execute(p);

            processes.put(camId, p);
            segments.add(new RecordingSegment(camId, fileName, file.toString(), now, null));
            log.info("录制已启动: {} → {}", camId, file);
        }
        return segments;
    }

    /** 停止全部录制。ManagedProcess 走 SIGTERM 宽限,ffmpeg 得以写完文件索引 */
    public synchronized void stopSegment() {
        processes.values().forEach(ManagedProcess::stop);
        processes.clear();
        log.info("录制片段已收尾");
    }

    public boolean recording() {
        return processes.values().stream().anyMatch(ManagedProcess::alive);
    }

    public Map<String, Boolean> aliveByCam() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        processes.forEach((camId, p) -> m.put(camId, p.alive()));
        return m;
    }

    private void drain(String camId, java.io.InputStream in) {
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.warn("[record-{}] {}", camId, line);
            }
        } catch (Exception ignored) {
            // 流关闭即进程退出
        }
    }
}

