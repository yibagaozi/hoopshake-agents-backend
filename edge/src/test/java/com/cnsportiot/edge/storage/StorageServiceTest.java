package com.cnsportiot.edge.storage;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.cloudsync.SessionBatchOrchestrator;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.StorageDtos.CleanupResult;
import com.cnsportiot.edge.dto.StorageDtos.SessionStorage;
import com.cnsportiot.edge.dto.StorageDtos.StorageStatus;
import com.cnsportiot.edge.service.SessionService;
import com.cnsportiot.edge.telemetry.TelemetryCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 录像存储:只删 meta.json 列出的录像;进行中/批处理中/KEEP/未出云的会话不删;空间不足时按最旧优先清理。 */
class StorageServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final long MB = 1024L * 1024;

    @TempDir
    Path root;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private EdgeProperties props;
    private SessionService sessionService;
    private SessionBatchOrchestrator batch;
    private final AtomicLong free = new AtomicLong(500 * DiskSpace.GB);
    private StorageService svc;

    @BeforeEach
    void setup() {
        props = new EdgeProperties();
        props.setDataRoot(root.toString());
        sessionService = mock(SessionService.class);
        when(sessionService.current()).thenReturn(SessionResponse.idle());
        batch = mock(SessionBatchOrchestrator.class);
        svc = new StorageService(props, mapper, sessionService, batch, new TelemetryCollector(props)) {
            @Override
            protected long usableBytes() {
                return free.get();
            }
        };
        svc.setClock(Clock.fixed(Instant.from(NOW), ZoneOffset.ofHours(8)));
    }

    /** 建一个会话目录:meta.json + 每路一个录像(大小 mbPerCam MB),下课于 NOW - daysAgo 天 */
    private UUID session(double daysAgo, int mbPerCam, String... extraFileNames) throws Exception {
        UUID id = UUID.randomUUID();
        Path dir = root.resolve("sessions").resolve(id.toString());
        Files.createDirectories(dir);
        OffsetDateTime ended = NOW.minusMinutes(Math.round(daysAgo * 24 * 60));
        OffsetDateTime started = ended.minusMinutes(45);
        List<Map<String, Object>> segs = new ArrayList<>();
        for (String cam : List.of("cam_01", "cam_02")) {
            String name = "20261001080000_" + cam + ".mkv";
            Files.write(dir.resolve(name), new byte[(int) (mbPerCam * MB)]);
            segs.add(seg(cam, name, started, ended));
        }
        for (String extra : extraFileNames) {
            segs.add(seg("cam_x", extra, started, ended));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", id.toString());
        meta.put("lessonId", null);
        meta.put("state", "ENDED");
        meta.put("startedAt", started.toString());
        meta.put("endedAt", ended.toString());
        meta.put("segments", segs);
        Files.writeString(dir.resolve("meta.json"), mapper.writeValueAsString(meta));
        return id;
    }

    private static Map<String, Object> seg(String cam, String name, OffsetDateTime s, OffsetDateTime e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("camId", cam);
        m.put("fileName", name);
        m.put("startedAt", s.toString());
        m.put("endedAt", e.toString());
        return m;
    }

    private Path dir(UUID id) {
        return root.resolve("sessions").resolve(id.toString());
    }

    private void published(UUID id, boolean ok) throws Exception {
        Files.createDirectories(dir(id).resolve("cloud"));
        Files.writeString(dir(id).resolve("cloud/published.json"), "{\"ok\":" + ok + "}");
    }

    private boolean hasVideo(UUID id) throws Exception {
        try (var s = Files.list(dir(id))) {
            return s.anyMatch(p -> p.toString().endsWith(".mkv"));
        }
    }

    private SessionStorage item(StorageStatus st, UUID id) {
        return st.items().stream().filter(i -> i.sessionId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void defaultPolicy_reportsUsage_deletesNothing() throws Exception {
        UUID old = session(40, 2);
        StorageStatus st = svc.status(null);
        assertThat(st.sessions()).isEqualTo(1);
        assertThat(st.rawBytes()).isEqualTo(4 * MB);
        assertThat(st.policy().cleanupEnabled()).isFalse();
        SessionStorage it = item(st, old);
        assertThat(it.eligible()).isFalse();
        assertThat(it.files()).hasSize(2);
        // 2 MB / 45 min ≈ 0.0062 Mbps
        assertThat(it.files().get(0).avgMbps()).isEqualTo(0.01);

        svc.scheduledCheck();
        CleanupResult r = svc.cleanup("scheduled", null);
        assertThat(r.sessions()).isZero();
        assertThat(hasVideo(old)).isTrue();
    }

    @Test
    void requirePublished_onlyCleansSuccessfullyPublishedSessions() throws Exception {
        UUID notPublished = session(10, 1);
        UUID publishedOk = session(10, 1);
        UUID publishedFailed = session(10, 1);
        published(publishedOk, true);
        published(publishedFailed, false);

        StorageStatus preview = svc.status(7);
        assertThat(preview.policy().preview()).isTrue();
        assertThat(item(preview, publishedOk).eligible()).isTrue();
        assertThat(item(preview, notPublished).reason()).contains("未出云");
        assertThat(item(preview, publishedFailed).reason()).contains("未全部成功");
        assertThat(hasVideo(publishedOk)).isTrue();  // 预览不删

        CleanupResult r = svc.cleanup("manual", 7);
        assertThat(r.sessionIds()).containsExactly(publishedOk);
        assertThat(r.bytes()).isEqualTo(2 * MB);
        assertThat(hasVideo(publishedOk)).isFalse();
        assertThat(hasVideo(notPublished)).isTrue();
        assertThat(hasVideo(publishedFailed)).isTrue();
    }

    @Test
    void retention_keepsMetaAndOtherFiles_andRecordsCleanup() throws Exception {
        props.getStorage().setRequirePublished(false);
        props.getStorage().setRawRetentionDays(7);
        UUID old = session(8, 1);
        UUID recent = session(3, 1);
        Files.createDirectories(dir(old).resolve("cloud"));
        Files.writeString(dir(old).resolve("cloud/ingest.json"), "{}");
        Files.write(dir(old).resolve("notes.mp4"), new byte[10]);  // 不在 meta 中:不动

        svc.scheduledCheck();

        assertThat(hasVideo(old)).isFalse();
        assertThat(dir(old).resolve("meta.json")).exists();
        assertThat(dir(old).resolve("cloud/ingest.json")).exists();
        assertThat(dir(old).resolve("notes.mp4")).exists();
        assertThat(mapper.readTree(Files.readString(dir(old).resolve("cleanup.json")))
                .path("runs").get(0).path("files")).hasSize(2);
        assertThat(hasVideo(recent)).isTrue();

        StorageStatus st = svc.status(null);
        assertThat(item(st, old).rawDeleted()).isTrue();
        assertThat(item(st, old).reason()).isEqualTo("录像已清理");
        assertThat(item(st, recent).reason()).startsWith("未到期");
    }

    @Test
    void keepActiveAndProcessingSessionsAreNeverCleaned() throws Exception {
        props.getStorage().setRequirePublished(false);
        UUID kept = session(30, 1);
        UUID active = session(30, 1);
        UUID processing = session(30, 1);
        UUID plain = session(30, 1);
        svc.setKeep(kept, true);
        when(sessionService.current()).thenReturn(new SessionResponse(active, null, SessionState.RECORDING,
                dir(active).toString(), NOW, null, List.of(), List.of()));
        when(batch.isProcessing(any())).thenAnswer(inv -> processing.equals(inv.getArgument(0)));

        CleanupResult r = svc.cleanup("manual", 7);

        assertThat(r.sessionIds()).containsExactly(plain);
        assertThat(hasVideo(kept)).isTrue();
        assertThat(hasVideo(active)).isTrue();
        assertThat(hasVideo(processing)).isTrue();

        assertThat(svc.setKeep(kept, false).keep()).isFalse();
        svc.cleanup("manual", 7);
        assertThat(hasVideo(kept)).isFalse();
    }

    @Test
    void unsafeFileNamesInMetaAreIgnored() throws Exception {
        props.getStorage().setRequirePublished(false);
        Path outside = root.resolve("outside.mkv");
        Files.write(outside, new byte[100]);
        UUID id = session(30, 1, "../../outside.mkv", "sub/x.mkv", "readme.txt");
        Files.createDirectories(dir(id).resolve("sub"));
        Files.write(dir(id).resolve("sub/x.mkv"), new byte[100]);
        Files.write(dir(id).resolve("readme.txt"), new byte[100]);

        svc.cleanup("manual", 7);

        assertThat(outside).exists();
        assertThat(dir(id).resolve("sub/x.mkv")).exists();
        assertThat(dir(id).resolve("readme.txt")).exists();
        assertThat(hasVideo(id)).isFalse();
    }

    @Test
    void lowDisk_cleansOldestEligibleFirst_untilAboveThreshold() throws Exception {
        props.getStorage().setRequirePublished(false);
        props.getStorage().setRawRetentionDays(30);   // 按天数都未到期
        props.getStorage().setMinFreeGb(50);
        props.getStorage().setPressureMinAgeHours(24);
        UUID oldest = session(5, 1);
        UUID middle = session(3, 1);
        UUID newest = session(2, 1);
        UUID justEnded = session(0.2, 1);   // 下课不到 24 小时:不提前清理
        // 每删一个会话(2 MB)“释放”10 GB,删两个后回到阈值以上
        free.set(35 * DiskSpace.GB);
        StorageService pressured = new StorageService(props, mapper, sessionService, batch,
                new TelemetryCollector(props)) {
            @Override
            protected long usableBytes() {
                long deleted = 0;
                for (UUID id : List.of(oldest, middle, newest, justEnded)) {
                    try {
                        if (!hasVideo(id)) {
                            deleted++;
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return free.get() + deleted * 10 * DiskSpace.GB;
            }
        };
        pressured.setClock(Clock.fixed(Instant.from(NOW), ZoneOffset.ofHours(8)));

        CleanupResult r = pressured.cleanup("scheduled", null);

        assertThat(r.sessionIds()).containsExactly(oldest, middle);
        assertThat(hasVideo(newest)).isTrue();
        assertThat(hasVideo(justEnded)).isTrue();
    }

    @Test
    void unfinishedRecentSessionWithoutEndIsTreatedAsActive() throws Exception {
        props.getStorage().setRequirePublished(false);
        UUID crashedLongAgo = session(30, 1);
        UUID maybeRecording = session(0, 1);
        for (UUID id : List.of(crashedLongAgo, maybeRecording)) {
            Path meta = dir(id).resolve("meta.json");
            Map<?, ?> m = mapper.readValue(Files.readString(meta), Map.class);
            Map<String, Object> mm = new LinkedHashMap<>();
            m.forEach((k, v) -> mm.put(String.valueOf(k), v));
            mm.put("endedAt", null);
            mm.put("state", "RECORDING");
            Files.writeString(meta, mapper.writeValueAsString(mm));
        }
        CleanupResult r = svc.cleanup("manual", 7);
        assertThat(r.sessionIds()).containsExactly(crashedLongAgo);
        assertThat(hasVideo(maybeRecording)).isTrue();
    }

    @Test
    void keepOnUnknownSession_notFound() {
        assertThatThrownBy(() -> svc.setKeep(UUID.randomUUID(), true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void bitrateEstimate() {
        assertThat(DiskSpace.parseBitrate("8M")).isEqualTo(8_000_000L);
        assertThat(DiskSpace.parseBitrate("4500k")).isEqualTo(4_500_000L);
        assertThat(DiskSpace.parseBitrate("2.5M")).isEqualTo(2_500_000L);
        assertThat(DiskSpace.parseBitrate("bad")).isEqualTo(8_000_000L);
        EdgeProperties p = new EdgeProperties();
        for (int i = 0; i < 4; i++) {
            p.getCameras().add(new EdgeProperties.Camera());
        }
        p.getEncode().setBitrate("8M");
        assertThat(DiskSpace.recordBytesPerSecond(p)).isEqualTo(4_000_000L);  // 4 路 × 8 Mbps = 4 MB/s
        assertThat(DiskSpace.usableBytes(root.resolve("not/yet/created"))).isPositive();
    }
}
