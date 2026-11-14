package com.cnsportiot.edge.service.impl;

import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.service.RecordService;
import com.cnsportiot.edge.service.SessionService;
import com.cnsportiot.edge.camera.CameraRegistry;
import com.cnsportiot.edge.capture.RecordingManager;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.CameraDescriptor;
import com.cnsportiot.edge.domain.enums.RecordState;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.RecordDtos.RecordCamera;
import com.cnsportiot.edge.dto.RecordDtos.RecordFile;
import com.cnsportiot.edge.dto.RecordDtos.RecordStartResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStatusResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStopResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordingEntry;
import com.cnsportiot.edge.dto.RecordDtos.TodayRecordingsResponse;
import com.cnsportiot.edge.dto.SessionDtos.SegmentItem;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.SessionDtos.StartSessionRequest;
import com.cnsportiot.edge.dto.SessionDtos.StopSessionResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class RecordServiceImpl implements RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordServiceImpl.class);
    private static final String META_FILE = "meta.json";
    private static final String SESSIONS_DIR = "sessions";

    private final EdgeProperties props;
    private final SessionService sessionService;
    private final RecordingManager recordingManager;
    private final CameraRegistry registry;
    private final ObjectMapper objectMapper;

    public RecordServiceImpl(EdgeProperties props,
                             SessionService sessionService,
                             RecordingManager recordingManager,
                             CameraRegistry registry,
                             ObjectMapper objectMapper) {
        this.props = props;
        this.sessionService = sessionService;
        this.recordingManager = recordingManager;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public RecordStartResponse start() {
        // lessonId 置空:未选课即纯录制;已选课时 SessionService 会自动沿用当前课程
        SessionResponse s = sessionService.start(new StartSessionRequest(null));
        return new RecordStartResponse(
                s.sessionId(), RecordState.from(s.state()), s.sessionId(),
                s.dataDir(), s.startedAt(), camerasOf(s));
    }

    @Override
    public RecordStopResponse stop() {
        SessionResponse before = sessionService.current();
        String outputDir = before.dataDir();
        OffsetDateTime startedAt = before.startedAt();

        StopSessionResponse s = sessionService.stop();
        long duration = startedAt == null ? 0
                : Duration.between(startedAt, s.endedAt()).toSeconds();

        return new RecordStopResponse(
                s.sessionId(), RecordState.IDLE, s.endedAt(), duration,
                outputDir, filesOf(s.segments()));
    }

    @Override
    public RecordStatusResponse status() {
        SessionResponse s = sessionService.current();
        if (s.sessionId() == null || s.state() == SessionState.ENDED) {
            return RecordStatusResponse.idle();
        }
        long elapsed = s.startedAt() == null ? 0
                : Duration.between(s.startedAt(), OffsetDateTime.now()).toSeconds();
        return new RecordStatusResponse(
                RecordState.from(s.state()), s.sessionId(), s.sessionId(),
                s.startedAt(), elapsed, camerasOf(s));
    }

    /**
     * 扫描 {dir}/sessions/ 下各会话的 meta.json,筛出今日的
     *
     * <p>不按目录名解析时间,而是读 meta.json —— 会话目录以 sessionId 命名,
     * 名字里没有时间信息,元数据才是唯一可靠来源。缺失或损坏的 meta.json 跳过不报错。
     */
    @Override
    public TodayRecordingsResponse today(String dir) {
        String root = (dir == null || dir.isBlank()) ? props.getDataRoot() : dir;
        assertUnderDataRoot(root);

        Path sessions = Path.of(root, SESSIONS_DIR);
        LocalDate today = LocalDate.now();
        List<RecordingEntry> records = new ArrayList<>();

        if (!Files.isDirectory(sessions)) {
            return new TodayRecordingsResponse(today, root, List.of());
        }
        try (Stream<Path> dirs = Files.list(sessions)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                RecordingEntry entry = parseEntry(d, today);
                if (entry != null) {
                    records.add(entry);
                }
            });
        } catch (IOException e) {
            log.error("扫描录制目录失败: {}", sessions, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "扫描录制目录失败");
        }
        records.sort(Comparator.comparing(RecordingEntry::startedAt).reversed());
        return new TodayRecordingsResponse(today, root, records);
    }

    /** 解析单个会话目录;非今日或元数据不可用则返回 null */
    private RecordingEntry parseEntry(Path sessionDir, LocalDate today) {
        Path meta = sessionDir.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(meta));
            OffsetDateTime startedAt = OffsetDateTime.parse(root.path("startedAt").asText());
            if (!startedAt.toLocalDate().equals(today)) {
                return null;
            }
            OffsetDateTime endedAt = root.hasNonNull("endedAt")
                    ? OffsetDateTime.parse(root.get("endedAt").asText()) : null;
            String state = root.path("state").asText();

            List<RecordFile> files = new ArrayList<>();
            Set<String> cams = new java.util.LinkedHashSet<>();
            for (JsonNode seg : root.path("segments")) {
                String camId = seg.path("camId").asText();
                String fileName = seg.path("fileName").asText();
                cams.add(camId);
                Path f = sessionDir.resolve(fileName);
                files.add(new RecordFile(camId, fileName,
                        Files.isRegularFile(f) ? sizeOf(f) : null,
                        segmentSeconds(seg)));
            }
            long duration = endedAt != null
                    ? Duration.between(startedAt, endedAt).toSeconds()
                    : Duration.between(startedAt, OffsetDateTime.now()).toSeconds();

            return new RecordingEntry(
                    UUID.fromString(root.path("sessionId").asText()),
                    root.hasNonNull("lessonId") ? UUID.fromString(root.get("lessonId").asText()) : null,
                    startedAt, endedAt, duration, cams.size(),
                    !"ENDED".equals(state), sessionDir.toString(), files);

        } catch (Exception e) {
            log.debug("跳过无法解析的会话目录: {}", sessionDir, e);
            return null;
        }
    }

    /** 防目录遍历:只允许扫 data-root 及其子目录 */
    private void assertUnderDataRoot(String dir) {
        Path root = Path.of(props.getDataRoot()).toAbsolutePath().normalize();
        Path target = Path.of(dir).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "dir 超出允许范围: " + dir);
        }
    }

    /** 已配置机位中,本段在录的标 RECORDING,其余标 FAILED(开录时离线被跳过) */
    private List<RecordCamera> camerasOf(SessionResponse s) {
        Map<String, Boolean> alive = recordingManager.aliveByCam();
        Map<String, String> fileByCam = s.segments().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SegmentItem::camId, SegmentItem::fileName, (a, b) -> b));

        List<RecordCamera> list = new ArrayList<>();
        for (CameraDescriptor cam : registry.all()) {
            String camId = cam.camId();
            if (Boolean.TRUE.equals(alive.get(camId))) {
                list.add(RecordCamera.recording(camId, fileByCam.get(camId)));
            } else {
                list.add(RecordCamera.failed(camId, "机位离线或录制进程已退出"));
            }
        }
        return list;
    }

    private List<RecordFile> filesOf(List<SegmentItem> segments) {
        return segments.stream()
                .map(seg -> new RecordFile(seg.camId(), seg.fileName(),
                        sizeOf(Path.of(seg.filePath())),
                        seg.endedAt() == null ? null
                                : Duration.between(seg.startedAt(), seg.endedAt()).toSeconds()))
                .toList();
    }

    private static Long sizeOf(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Long segmentSeconds(JsonNode seg) {
        if (!seg.hasNonNull("endedAt")) {
            return null;
        }
        return Duration.between(
                OffsetDateTime.parse(seg.get("startedAt").asText()),
                OffsetDateTime.parse(seg.get("endedAt").asText())).toSeconds();
    }
}

