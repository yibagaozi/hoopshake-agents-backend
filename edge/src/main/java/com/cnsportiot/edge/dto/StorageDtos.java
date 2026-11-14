package com.cnsportiot.edge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 录像存储状态与清理(Edge API /local/storage) */
public final class StorageDtos {

    private StorageDtos() {
    }

    public record StorageStatus(
            String dataRoot,
            long totalBytes,
            long freeBytes,
            /** 按编码码率 × 机位数估算的剩余可录分钟数 */
            long minutesLeft,
            long rawBytes,
            int sessions,
            /** 按当前(或预览)策略可清理的会话与字节数 */
            int eligibleSessions,
            long eligibleBytes,
            Policy policy,
            List<SessionStorage> items) {
    }

    public record Policy(
            int rawRetentionDays,
            boolean cleanupEnabled,
            boolean requirePublished,
            long minFreeGb,
            long pressureMinAgeHours,
            long blockStartFreeGb,
            /** true = 本次结果是带 retentionDays 参数的预览,不是当前生效配置 */
            boolean preview) {
    }

    public record SessionStorage(
            UUID sessionId,
            UUID lessonId,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String state,
            long rawBytes,
            List<RawFile> files,
            /** 算法交接文件 cloud/ingest.json 是否存在 */
            boolean handoff,
            /** 出云结果(cloud/published.json);null = 未出云 */
            Boolean published,
            boolean keep,
            boolean rawDeleted,
            boolean eligible,
            String reason) {
    }

    public record RawFile(
            String camId,
            String fileName,
            Long bytes,
            Long seconds,
            /** 平均码率,便于核对编码设置 */
            Double avgMbps) {
    }

    public record CleanupResult(
            OffsetDateTime at,
            String trigger,
            int sessions,
            int files,
            long bytes,
            long freeBytesAfter,
            List<UUID> sessionIds) {
    }
}
