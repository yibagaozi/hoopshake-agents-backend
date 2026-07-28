package com.cnsportiot.edge.service.impl;


import com.cnsportiot.edge.capture.CaptureManager;
import com.cnsportiot.edge.capture.FfmpegCommandBuilder;
import com.cnsportiot.edge.capture.MediaMtxManager;
import com.cnsportiot.edge.capture.RecordingManager;
import com.cnsportiot.edge.camera.CameraRegistry;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.CameraDescriptor;
import com.cnsportiot.edge.domain.CameraStatus;
import com.cnsportiot.edge.dto.ConsoleDtos.CameraState;
import com.cnsportiot.edge.dto.ConsoleDtos.CaptureState;
import com.cnsportiot.edge.dto.ConsoleDtos.DiskState;
import com.cnsportiot.edge.domain.enums.ProcessState;
import com.cnsportiot.edge.dto.ConsoleDtos.StateResponse;
import com.cnsportiot.edge.service.CaptureService;
import com.cnsportiot.edge.service.LessonContextService;
import com.cnsportiot.edge.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CaptureServiceImpl implements CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureServiceImpl.class);

    /** 四路 8Mbps 估算:8 * 4 / 8 = 4 MB/s */
    private static final long BYTES_PER_SECOND_ALL_CAMS = 4L * 1024 * 1024;

    private final EdgeProperties props;
    private final CameraRegistry registry;
    private final CaptureManager captureManager;
    private final RecordingManager recordingManager;
    private final MediaMtxManager mediaMtx;
    private final FfmpegCommandBuilder commandBuilder;
    private final SessionService sessionService;
    private final LessonContextService lessonContextService;

    public CaptureServiceImpl(EdgeProperties props,
                              CameraRegistry registry,
                              CaptureManager captureManager,
                              RecordingManager recordingManager,
                              MediaMtxManager mediaMtx,
                              FfmpegCommandBuilder commandBuilder,
                              SessionService sessionService,
                              LessonContextService lessonContextService) {
        this.props = props;
        this.registry = registry;
        this.captureManager = captureManager;
        this.recordingManager = recordingManager;
        this.mediaMtx = mediaMtx;
        this.commandBuilder = commandBuilder;
        this.sessionService = sessionService;
        this.lessonContextService = lessonContextService;
    }

    /** 应用就绪后自动拉起:先 mediamtx,就绪后再推流,顺序不能反 */
    @EventListener(ApplicationReadyEvent.class)
    @Override
    public void startAll() {
        try {
            mediaMtx.start();
            captureManager.startAll();
        } catch (RuntimeException e) {
            log.error("采集自动启动失败,可经 /local/capture/restart 手动重试", e);
        }
    }

    @Override
    public void restart(String camId) {
        if (camId == null || camId.isBlank()) {
            captureManager.restartAll();
        } else {
            captureManager.restart(camId);
        }
    }

    @Override
    public StateResponse state() {
        long stale = registry.staleFrameMillis();

        List<CameraState> cameras = registry.all().stream()
                .map(cam -> toCameraState(cam, registry.status(cam.camId()), stale))
                .toList();

        Map<String, ProcessState> processes = new LinkedHashMap<>();
        registry.all().forEach(cam ->
                processes.put(cam.camId(), captureManager.stateOf(cam.camId())));

        return new StateResponse(
                props.getEdgeId(),
                lessonContextService.current(),
                sessionService.current(),
                cameras,
                new CaptureState(mediaMtx.state(), recordingManager.recording(), processes),
                diskState());
    }

    private CameraState toCameraState(CameraDescriptor cam, CameraStatus st, long stale) {
        return new CameraState(
                cam.camId(), cam.role(), cam.anchor(),
                st.online(stale), st.signal(stale), st.fps(),
                captureManager.stateOf(cam.camId()),
                commandBuilder.rtspUrl(cam.camId()));
    }

    private DiskState diskState() {
        File root = new File(props.getDataRoot());
        long free = root.getUsableSpace();
        long total = root.getTotalSpace();
        long minutes = free / BYTES_PER_SECOND_ALL_CAMS / 60;
        return new DiskState(free, total, minutes);
    }
}

