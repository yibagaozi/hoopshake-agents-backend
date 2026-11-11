package com.cnsportiot.edge.storage;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.cloudsync.SessionBatchOrchestrator;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.StorageDtos.CleanupResult;
import com.cnsportiot.edge.dto.StorageDtos.Policy;
import com.cnsportiot.edge.dto.StorageDtos.RawFile;
import com.cnsportiot.edge.dto.StorageDtos.SessionStorage;
import com.cnsportiot.edge.dto.StorageDtos.StorageStatus;
import com.cnsportiot.edge.service.SessionService;
import com.cnsportiot.edge.telemetry.TelemetryCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 录像存储:统计 {data-root}/sessions/ 下各会话的录像占用,按保留策略清理录像文件。
 *
 * <p>只删除 meta.json 中列出的分段录像文件(.mkv/.mp4,且必须位于该会话目录内),
 * meta.json、cloud/、cleanup.json 等小文件保留,会话记录仍可查。以下会话从不清理:
 * 进行中的会话、批处理中的会话、目录内有 {@code KEEP} 文件的会话、
 * {@code requirePublished=true} 时尚未成功出云的会话。
 *
 * <p>默认 {@code rawRetentionDays=0}:不自动删除任何文件,只统计与告警。
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    static final String SESSIONS_DIR = "sessions";
    static final String META_FILE = "meta.json";
    static final String KEEP_FILE = "KEEP";
    static final String CLEANUP_FILE = "cleanup.json";
    static final String PUBLISHED_REL = "cloud/published.json";
    private static final Set<String> VIDEO_EXT = Set.of("mkv", "mp4");

    private final EdgeProperties props;
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;
    private final SessionBatchOrchestrator batchOrchestrator;
    private final TelemetryCollector telemetry;

    private Clock clock = Clock.systemDefaultZone();

    public StorageService(EdgeProperties props, ObjectMapper objectMapper, SessionService sessionService,
                          SessionBatchOrchestrator batchOrchestrator, TelemetryCollector telemetry) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.batchOrchestrator = batchOrchestrator;
        this.telemetry = telemetry;
    }

    public int configuredRetentionDays() {
        return props.getStorage().getRawRetentionDays();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 可用空间;测试可覆写。 */
    protected long usableBytes() {
        return DiskSpace.usableBytes(root());
    }

    // ---- 对外 ----

    /** 存储状态;{@code retentionDays} 非空时按该天数预览可清理项(不删除)。 */
    public StorageStatus status(Integer retentionDays) {
        int days = effectiveDays(retentionDays);
        List<Entry> entries = scan();
        UUID active = activeSessionId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<SessionStorage> items = new ArrayList<>();
        long raw = 0;
        long eligibleBytes = 0;
        int eligible = 0;
        for (Entry e : entries) {
            Verdict v = verdict(e, days, active, now);
            raw += e.rawBytes();
            if (v.eligible()) {
                eligible++;
                eligibleBytes += e.rawBytes();
            }
            items.add(toItem(e, v));
        }
        long free = usableBytes();
        return new StorageStatus(root().toString(), DiskSpace.totalBytes(root()), free,
                free / DiskSpace.recordBytesPerSecond(props) / 60,
                raw, entries.size(), eligible, eligibleBytes,
                policy(days, retentionDays != null), items);
    }

    /**
     * 执行一次清理:① 下课超过保留天数的可清理会话;② 可用空间低于 minFreeGb 时,
     * 再按下课时间从旧到新清理可清理会话(至少下课 pressureMinAgeHours 小时),直到空间恢复。
     *
     * @param retentionDays 手动指定天数;null 用配置。两者都 ≤0 时不清理。
     */
    public synchronized CleanupResult cleanup(String trigger, Integer retentionDays) {
        int days = effectiveDays(retentionDays);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<UUID> cleaned = new ArrayList<>();
        int files = 0;
        long bytes = 0;
        if (days <= 0) {
            return new CleanupResult(now, trigger, 0, 0, 0, usableBytes(), List.of());
        }
        UUID active = activeSessionId();
        List<Entry> entries = scan();

        for (Entry e : entries) {
            if (verdict(e, days, active, now).eligible()) {
                long[] r = deleteRaw(e, trigger, "超过保留天数 " + days);
                if (r[0] > 0) {
                    cleaned.add(e.sessionId());
                    files += (int) r[0];
                    bytes += r[1];
                }
            }
        }

        long minFree = props.getStorage().getMinFreeGb() * DiskSpace.GB;
        if (minFree > 0 && usableBytes() < minFree) {
            Duration minAge = Duration.ofHours(Math.max(0, props.getStorage().getPressureMinAgeHours()));
            List<Entry> byAge = entries.stream()
                    .filter(e -> !cleaned.contains(e.sessionId()))
                    .filter(e -> blocker(e, active) == null)
                    .filter(e -> !e.endTime().plus(minAge).isAfter(now))
                    .sorted(Comparator.comparing(Entry::endTime))
                    .toList();
            for (Entry e : byAge) {
                if (usableBytes() >= minFree) {
                    break;
                }
                long[] r = deleteRaw(e, trigger, "可用空间低于 " + props.getStorage().getMinFreeGb() + " GB");
                if (r[0] > 0) {
                    cleaned.add(e.sessionId());
                    files += (int) r[0];
                    bytes += r[1];
                }
            }
            if (usableBytes() < minFree) {
                log.warn("录像磁盘可用空间仍低于 {} GB(当前 {} GB),已无可清理会话;"
                                + "请检查 KEEP 标记、出云状态(requirePublished)或扩容",
                        props.getStorage().getMinFreeGb(), usableBytes() / DiskSpace.GB);
            }
        }
        long freeAfter = usableBytes();
        if (!cleaned.isEmpty()) {
            log.info("录像清理完成 trigger={} 会话={} 文件={} 释放={} MB 剩余可用={} GB",
                    trigger, cleaned.size(), files, bytes / (1024 * 1024), freeAfter / DiskSpace.GB);
            telemetry.metric("edge.storage.cleanup.bytes", bytes, "bytes", Map.of("trigger", trigger));
        }
        return new CleanupResult(now, trigger, cleaned.size(), files, bytes, freeAfter, List.copyOf(cleaned));
    }

    /** 标记/取消保留:目录内放一个 KEEP 文件,自动清理永远跳过它(如留作测试集、争议回看)。 */
    public SessionStorage setKeep(UUID sessionId, boolean keep) {
        Path dir = root().resolve(SESSIONS_DIR).resolve(sessionId.toString());
        if (!Files.isRegularFile(dir.resolve(META_FILE))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }
        try {
            if (keep) {
                if (!Files.exists(dir.resolve(KEEP_FILE))) {
                    Files.writeString(dir.resolve(KEEP_FILE), OffsetDateTime.now(clock).toString());
                }
            } else {
                Files.deleteIfExists(dir.resolve(KEEP_FILE));
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "KEEP 标记写入失败: " + e.getMessage());
        }
        Entry e = read(dir);
        return toItem(e, verdict(e, effectiveDays(null), activeSessionId(), OffsetDateTime.now(clock)));
    }

    /** 定时:空间告警 + 指标;开启保留天数时执行清理。 */
    @Scheduled(fixedDelayString = "${hoopshake.edge.storage.check-interval:10m}", initialDelayString = "PT1M")
    public void scheduledCheck() {
        try {
            long free = usableBytes();
            long minFree = props.getStorage().getMinFreeGb() * DiskSpace.GB;
            if (minFree > 0 && free < minFree) {
                log.warn("录像磁盘可用空间 {} GB,低于告警线 {} GB(约可录 {} 分钟){}",
                        free / DiskSpace.GB, props.getStorage().getMinFreeGb(),
                        free / DiskSpace.recordBytesPerSecond(props) / 60,
                        props.getStorage().getRawRetentionDays() > 0 ? ",开始清理" : ";未开启自动清理(storage.raw-retention-days=0)");
            }
            if (props.getStorage().getRawRetentionDays() > 0) {
                cleanup("scheduled", null);
            }
            StorageStatus s = status(null);
            telemetry.metric("edge.storage.raw.bytes", s.rawBytes(), "bytes", null);
            telemetry.metric("edge.storage.sessions", s.sessions(), "count", null);
            telemetry.metric("edge.storage.eligible.bytes", s.eligibleBytes(), "bytes", null);
            telemetry.metric("edge.storage.minutes.left", s.minutesLeft(), "minutes", null);
        } catch (RuntimeException e) {
            log.warn("录像存储检查失败: {}", e.toString());
        }
    }

    // ---- 内部 ----

    private record Entry(Path dir, UUID sessionId, UUID lessonId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                         String state, List<Seg> segments, boolean handoff, Boolean published, boolean keep,
                         boolean cleanupRecord) {
        long rawBytes() {
            return segments.stream().mapToLong(s -> s.bytes() == null ? 0 : s.bytes()).sum();
        }

        boolean rawDeleted() {
            return cleanupRecord && segments.stream().allMatch(s -> s.bytes() == null);
        }

        /** 下课时间;异常中断(无 endedAt)的会话按开始时间算 */
        OffsetDateTime endTime() {
            return endedAt != null ? endedAt : startedAt;
        }
    }

    private record Seg(String camId, String fileName, Path file, Long bytes, Long seconds) {
    }

    private record Verdict(boolean eligible, String reason) {
    }

    private Path root() {
        return Path.of(props.getDataRoot() == null ? "." : props.getDataRoot());
    }

    private int effectiveDays(Integer override) {
        return override != null ? override : props.getStorage().getRawRetentionDays();
    }

    private Policy policy(int days, boolean preview) {
        EdgeProperties.Storage s = props.getStorage();
        return new Policy(days, days > 0, s.isRequirePublished(), s.getMinFreeGb(), s.getPressureMinAgeHours(),
                s.getBlockStartFreeGb(), preview);
    }

    private UUID activeSessionId() {
        try {
            SessionResponse cur = sessionService.current();
            if (cur != null && (cur.state() == SessionState.RECORDING || cur.state() == SessionState.PAUSED)) {
                return cur.sessionId();
            }
        } catch (RuntimeException ignored) {
            // 取不到当前会话时按无进行中会话处理;进行中的会话 meta 中 state 仍为 RECORDING/PAUSED,下面再挡一次
        }
        return null;
    }

    /** 与保留天数无关的不可清理原因;null = 可清理 */
    private String blocker(Entry e, UUID active) {
        if (e.rawBytes() == 0) {
            return e.rawDeleted() ? "录像已清理" : "无录像文件";
        }
        if (e.keep()) {
            return "已标记保留(KEEP)";
        }
        if (e.sessionId().equals(active)) {
            return "进行中";
        }
        if (e.endedAt() == null && isRecent(e)) {
            // edge 重启后内存里没有当前会话;另有会话在录时,这一段也可能是刚异常中断、尚未处理的
            return "进行中";
        }
        if (batchOrchestrator.isProcessing(e.sessionId())) {
            return "批处理中";
        }
        if (props.getStorage().isRequirePublished() && !Boolean.TRUE.equals(e.published())) {
            return e.published() == null ? "未出云(requirePublished=true)" : "出云未全部成功(requirePublished=true)";
        }
        return null;
    }

    /** 无 endedAt 且 12 小时内开始的会话视为可能仍在录(edge 重启后内存里没有当前会话) */
    private boolean isRecent(Entry e) {
        return e.startedAt() != null && e.startedAt().plusHours(12).isAfter(OffsetDateTime.now(clock));
    }

    private Verdict verdict(Entry e, int days, UUID active, OffsetDateTime now) {
        String b = blocker(e, active);
        if (b != null) {
            return new Verdict(false, b);
        }
        if (days <= 0) {
            return new Verdict(false, "未开启自动清理");
        }
        OffsetDateTime due = e.endTime().plusDays(days);
        if (due.isAfter(now)) {
            long hours = Duration.between(now, due).toHours();
            return new Verdict(false, "未到期(还剩约 " + (hours >= 24 ? hours / 24 + " 天" : hours + " 小时") + ")");
        }
        return new Verdict(true, "超过保留天数 " + days);
    }

    private List<Entry> scan() {
        Path sessions = root().resolve(SESSIONS_DIR);
        if (!Files.isDirectory(sessions)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessions)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                Entry e = read(d);
                if (e != null) {
                    out.add(e);
                }
            });
        } catch (IOException e) {
            log.warn("扫描会话目录失败: {}", sessions, e);
        }
        out.sort(Comparator.comparing(Entry::endTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** 读一个会话目录;没有可解析的 meta.json 返回 null(不当作会话,不清理) */
    private Entry read(Path dir) {
        Path meta = dir.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(meta));
            UUID sid = UUID.fromString(root.path("sessionId").asText(dir.getFileName().toString()));
            UUID lesson = root.hasNonNull("lessonId") ? UUID.fromString(root.get("lessonId").asText()) : null;
            OffsetDateTime started = root.hasNonNull("startedAt") ? OffsetDateTime.parse(root.get("startedAt").asText()) : null;
            OffsetDateTime ended = root.hasNonNull("endedAt") ? OffsetDateTime.parse(root.get("endedAt").asText()) : null;
            if (started == null) {
                return null;
            }
            List<Seg> segs = new ArrayList<>();
            for (JsonNode seg : root.path("segments")) {
                String fileName = seg.path("fileName").asText(null);
                Path file = videoFileIn(dir, fileName);
                if (file == null) {
                    continue;
                }
                Long bytes = Files.isRegularFile(file) ? Files.size(file) : null;
                Long seconds = seg.hasNonNull("startedAt") && seg.hasNonNull("endedAt")
                        ? Duration.between(OffsetDateTime.parse(seg.get("startedAt").asText()),
                        OffsetDateTime.parse(seg.get("endedAt").asText())).toSeconds()
                        : null;
                segs.add(new Seg(seg.path("camId").asText(null), fileName, file, bytes, seconds));
            }
            return new Entry(dir, sid, lesson, started, ended, root.path("state").asText(null), segs,
                    Files.isRegularFile(dir.resolve(props.getPublish().getHandoffRelPath())),
                    readPublished(dir), Files.exists(dir.resolve(KEEP_FILE)),
                    Files.isRegularFile(dir.resolve(CLEANUP_FILE)));
        } catch (Exception e) {
            log.debug("跳过无法解析的会话目录: {}", dir, e);
            return null;
        }
    }

    /** 只接受会话目录下一层的 .mkv/.mp4 文件名,防止 meta.json 被改写后删到别处 */
    private static Path videoFileIn(Path dir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path base = dir.toAbsolutePath().normalize();
        Path file = base.resolve(fileName).normalize();
        if (!base.equals(file.getParent())) {
            return null;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !VIDEO_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return null;
        }
        return file;
    }

    private Boolean readPublished(Path dir) {
        Path p = dir.resolve(PUBLISHED_REL);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(p)).path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** @return [删除文件数, 释放字节数] */
    private long[] deleteRaw(Entry e, String trigger, String reason) {
        List<Map<String, Object>> deleted = new ArrayList<>();
        long bytes = 0;
        for (Seg s : e.segments()) {
            if (s.bytes() == null) {
                continue;
            }
            try {
                if (Files.deleteIfExists(s.file())) {
                    bytes += s.bytes();
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("camId", s.camId());
                    f.put("fileName", s.fileName());
                    f.put("bytes", s.bytes());
                    deleted.add(f);
                }
            } catch (IOException ex) {
                log.warn("删除录像失败 session={} file={}: {}", e.sessionId(), s.file(), ex.getMessage());
            }
        }
        if (!deleted.isEmpty()) {
            appendCleanupRecord(e.dir(), trigger, reason, deleted, bytes);
            log.info("已清理录像 session={} lesson={} 文件={} 释放={} MB 原因={}",
                    e.sessionId(), e.lessonId(), deleted.size(), bytes / (1024 * 1024), reason);
        }
        return new long[]{deleted.size(), bytes};
    }

    private void appendCleanupRecord(Path dir, String trigger, String reason,
                                     List<Map<String, Object>> files, long bytes) {
        Path p = dir.resolve(CLEANUP_FILE);
        List<Object> runs = new ArrayList<>();
        try {
            if (Files.isRegularFile(p)) {
                JsonNode old = objectMapper.readTree(Files.readString(p)).path("runs");
                if (old.isArray()) {
                    for (JsonNode n : old) {
                        runs.add(objectMapper.treeToValue(n, Map.class));
                    }
                }
            }
        } catch (Exception ignored) {
            // 旧记录损坏:重写
        }
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("at", OffsetDateTime.now(clock).toString());
        run.put("trigger", trigger);
        run.put("reason", reason);
        run.put("bytes", bytes);
        run.put("files", files);
        runs.add(run);
        try {
            Files.writeString(p, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("runs", runs)));
        } catch (Exception ex) {
            log.warn("cleanup.json 写入失败 {}: {}", p, ex.getMessage());
        }
    }

    private SessionStorage toItem(Entry e, Verdict v) {
        List<RawFile> files = e.segments().stream().map(s -> new RawFile(s.camId(), s.fileName(), s.bytes(),
                s.seconds(),
                s.bytes() != null && s.seconds() != null && s.seconds() > 0
                        ? Math.round(s.bytes() * 8.0 / s.seconds() / 1e4) / 100.0 : null)).toList();
        return new SessionStorage(e.sessionId(), e.lessonId(), e.startedAt(), e.endedAt(), e.state(),
                e.rawBytes(), files, e.handoff(), e.published(), e.keep(), e.rawDeleted(), v.eligible(), v.reason());
    }
}
