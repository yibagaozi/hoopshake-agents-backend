package com.cnsportiot.edge.service.impl;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.service.LessonContextService;
import com.cnsportiot.edge.config.CameraRegistry;
import com.cnsportiot.edge.capture.CaptureManager;
import com.cnsportiot.edge.capture.RecordingManager;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.CameraDescriptor;
import com.cnsportiot.edge.domain.LessonContext;
import com.cnsportiot.edge.domain.RecordingSegment;
import com.cnsportiot.edge.domain.RecordingSession;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.dto.SessionDtos.RecordingHealth;
import com.cnsportiot.edge.dto.SessionDtos.SegmentItem;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.SessionDtos.StartSessionRequest;
import com.cnsportiot.edge.dto.SessionDtos.StopSessionResponse;
import com.cnsportiot.edge.service.SessionService;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 单机单会话模型:同一时刻只允许一个录制会话 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceImpl.class);
    private static final String META_FILE = "meta.json";

    private final EdgeProperties props;
    private final CameraRegistry registry;
    private final CaptureManager captureManager;
    private final RecordingManager recordingManager;
    private final LessonContextService lessonContextService;
    private final ObjectMapper objectMapper;

    private final AtomicReference<RecordingSession> current = new AtomicReference<>();

    public SessionServiceImpl(EdgeProperties props,
                              CameraRegistry registry,
                              CaptureManager captureManager,
                              RecordingManager recordingManager,
                              LessonContextService lessonContextService,
                              ObjectMapper objectMapper) {
        this.props = props;
        this.registry = registry;
        this.captureManager = captureManager;
        this.recordingManager = recordingManager;
        this.lessonContextService = lessonContextService;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized SessionResponse start(StartSessionRequest request) {
        RecordingSession existing = current.get();
        if (existing != null
                && (existing.state() == SessionState.RECORDING || existing.state() == SessionState.PAUSED)) {
            throw new BusinessException(EdgeErrorCode.SESSION_ALREADY_RUNNING);
        }
        if (!captureManager.anyRunning()) {
            throw new BusinessException(EdgeErrorCode.CAPTURE_NOT_RUNNING, "采集未启动,无法录制");
        }

        // 优先取请求参数,其次取已选课程;都没有则为开发阶段的纯录制
        UUID lessonId = request.lessonId() != null
                ? request.lessonId()
                : lessonContextService.context().map(LessonContext::lessonId).orElse(null);
        if (lessonId == null) {
            log.warn("无课程的纯录制,云端汇总需开启 allow-orphan-session");
        }

        UUID sessionId = UUID.randomUUID();
        Path dataDir = sessionDir(sessionId);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.error("创建数据目录失败: {}", dataDir, e);
            throw new BusinessException(EdgeErrorCode.DATA_DIR_UNWRITABLE, dataDir.toString());
        }

        RecordingSession session = new RecordingSession(
                sessionId, lessonId, dataDir.toString(), OffsetDateTime.now());

        List<String> online = requireAnyOnline();
        List<RecordingSegment> segments;
        try {
            segments = recordingManager.startSegment(dataDir, online);
        } catch (RuntimeException e) {
            log.error("录制启动失败,回滚会话 {}", sessionId, e);
            recordingManager.stopSegment();
            throw new BusinessException(EdgeErrorCode.RECORDING_START_FAILED, e.getMessage());
        }
        session.addSegments(segments);
        current.set(session);

        writeMeta(session);
        // TODO 预留:落 spool 一条 §10.1 事件(status=CREATED),联网后补传
        log.info("会话已开始 sessionId={} lessonId={} 录制 {} 路 dataDir={}",
                sessionId, lessonId, online.size(), dataDir);
        return toResponse(session);
    }

    @Override
    public synchronized SessionResponse pause() {
        RecordingSession session = requireState(SessionState.RECORDING);
        recordingManager.stopSegment();
        session.pause(OffsetDateTime.now());
        writeMeta(session);
        log.info("会话已暂停 sessionId={}", session.sessionId());
        return toResponse(session);
    }

    @Override
    public synchronized SessionResponse resume() {
        RecordingSession session = requireState(SessionState.PAUSED);
        if (!captureManager.anyRunning()) {
            throw new BusinessException(EdgeErrorCode.CAPTURE_NOT_RUNNING, "采集未启动,无法继续录制");
        }
        // 新时间戳 → 新一段文件,ffmpeg 无法真正挂起,继续即重新起进程。
        // 期间可能有机位掉线,按当前在线集合重新起,不因少一路而拒绝继续上课
        List<String> online = requireAnyOnline();
        session.addSegments(recordingManager.startSegment(Path.of(session.dataDir()), online));
        session.resume();
        writeMeta(session);
        log.info("会话已继续 sessionId={} 录制 {} 路", session.sessionId(), online.size());
        return toResponse(session);
    }

    @Override
    public synchronized StopSessionResponse stop() {
        RecordingSession session = current.get();
        if (session == null
                || (session.state() != SessionState.RECORDING && session.state() != SessionState.PAUSED)) {
            throw new BusinessException(EdgeErrorCode.SESSION_NOT_RUNNING);
        }

        recordingManager.stopSegment();
        session.end(OffsetDateTime.now());
        writeMeta(session);
        // TODO 预留:落 spool 一条 §10.1 事件(status=RECORDED,带 dataDir/recordedAt),联网后补传
        log.info("会话已结束 sessionId={} 片段数={}", session.sessionId(), session.segments().size());

        List<SegmentItem> segments = toSegmentItems(session);
        return new StopSessionResponse(
                session.sessionId(), session.state(), session.endedAt(), segments.size(), segments);
    }

    @Override
    public SessionResponse current() {
        RecordingSession session = current.get();
        if (session != null && session.state() != SessionState.ENDED) {
            return toResponse(session);
        }
        // 无进行中的会话:已选课则 READY,否则 IDLE
        return lessonContextService.context()
                .map(ctx -> SessionResponse.ready(ctx.lessonId()))
                .orElseGet(SessionResponse::idle);
    }

    @Override
    public RecordingHealth health() {
        return new RecordingHealth(
                current().state(), recordingManager.recording(), recordingManager.aliveByCam());
    }

    /**
     * 取当前在线机位;全部离线才拒绝开录
     *
     * <p>少一路仍允许上课——录三路远好过整节课中断。被跳过的机位经
     * {@code unavailableCameras} 回给操作台做显著提示。
     */
    private List<String> requireAnyOnline() {
        List<String> online = registry.onlineCamIds();
        if (online.isEmpty()) {
            throw new BusinessException(EdgeErrorCode.CAMERA_OFFLINE, "全部机位离线,无法录制");
        }
        List<String> offline = registry.offlineCamIds();
        if (!offline.isEmpty()) {
            log.warn("降级录制:跳过离线机位 {}", offline);
        }
        return online;
    }

    private RecordingSession requireState(SessionState expected) {
        RecordingSession session = current.get();
        if (session == null || session.state() != expected) {
            throw new BusinessException(EdgeErrorCode.SESSION_STATE_INVALID,
                    "当前状态 " + (session == null ? SessionState.IDLE : session.state())
                            + ",该操作要求 " + expected);
        }
        return session;
    }

    private Path sessionDir(UUID sessionId) {
        return Path.of(props.getDataRoot(), "sessions", sessionId.toString());
    }

    /** meta.json 与录制文件同目录,便于后续 pipeline 定位会话上下文 */
    private void writeMeta(RecordingSession session) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", session.sessionId());
        meta.put("lessonId", session.lessonId());
        meta.put("edgeId", props.getEdgeId());
        meta.put("state", session.state());
        meta.put("startedAt", session.startedAt());
        meta.put("endedAt", session.endedAt());
        meta.put("segments", session.segments());
        lessonContextService.context().ifPresent(ctx -> meta.put("lesson", ctx));
        try {
            Files.writeString(Path.of(session.dataDir(), META_FILE),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        } catch (IOException e) {
            log.warn("meta.json 写入失败,不影响录制: {}", session.dataDir(), e);
        }
    }

    private SessionResponse toResponse(RecordingSession s) {
        return new SessionResponse(s.sessionId(), s.lessonId(), s.state(),
                s.dataDir(), s.startedAt(), s.endedAt(), toSegmentItems(s),
                currentlyUnavailable(s));
    }

    /** 已配置但本段未在录的机位 = 开录时离线被跳过的 */
    private List<String> currentlyUnavailable(RecordingSession s) {
        if (s.state() != SessionState.RECORDING) {
            return List.of();
        }
        Set<String> recordingCams = recordingManager.aliveByCam().keySet();
        return registry.all().stream()
                .map(CameraDescriptor::camId)
                .filter(camId -> !recordingCams.contains(camId))
                .toList();
    }

    private List<SegmentItem> toSegmentItems(RecordingSession s) {
        return s.segments().stream()
                .map(seg -> new SegmentItem(seg.camId(), seg.fileName(), seg.filePath(),
                        seg.startedAt(), seg.endedAt()))
                .toList();
    }
}

