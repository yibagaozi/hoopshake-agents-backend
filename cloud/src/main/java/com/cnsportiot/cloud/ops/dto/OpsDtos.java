package com.cnsportiot.cloud.ops.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 运维只读接口的响应 DTO */
public final class OpsDtos {

    private OpsDtos() {}

    /** 派生健康态(读时按 last_seen_at 计算) */
    public enum EdgeHealth { ONLINE, STALE, OFFLINE }

    // 4.1 首屏总览

    public record OverviewResponse(
            OffsetDateTime generatedAt,
            BusinessResponse business,
            AgentQualityResponse agent,
            SystemHealthResponse system,
            EdgeSummary edge) {}

    // 4.2 业务量

    public record BusinessResponse(
            long students,
            long teachers,
            LessonCounts lessons,
            long trainingSessions,
            DataVolume dataVolume) {}

    public record LessonCounts(long total, long planned, long ongoing, long finished) {}

    public record DataVolume(
            long actionClips,
            long instantFeedback,
            long chatSessions,
            long chatMessages,
            long knowledgeDocuments) {}

    // 4.3 Agent 表现(近窗)

    public record AgentQualityResponse(
            int windowHours,
            long answeredRuns,
            long degradedRuns,
            Double degradedRate,
            long ragHitRuns,
            Double ragHitRate,
            Double avgAnswerChars,
            ToolCounts tool,
            Double toolErrorRate) {}

    public record ToolCounts(long ok, long deny, long error) {}

    // 4.4 系统健康(韧性快照)

    public record SystemHealthResponse(
            boolean llmEnabled,
            CircuitSnapshot circuit,
            StreamSnapshot llmStreams,
            RateLimitSnapshot askRateLimit) {}

    /** 熔断状态;LLM 未启用时 state 为 null */
    public record CircuitSnapshot(String state) {}

    public record StreamSnapshot(int active, int available, int max, long rejected) {}

    public record RateLimitSnapshot(long rejected, int trackedKeys) {}

    // 4.5 边缘设备

    public record EdgeSummary(long total, long online, long stale, long offline) {}

    public record EdgeDeviceResponse(
            String deviceId,
            String name,
            String courtId,
            String reportedStatus,
            EdgeHealth health,
            String appVersion,
            String firmware,
            String ipAddress,
            Map<String, Object> metrics,
            String lastError,
            OffsetDateTime lastSeenAt) {}

    // 4.6 心跳响应

    public record EdgeHeartbeatAck(String deviceId, EdgeHealth health, OffsetDateTime lastSeenAt) {}

    /** 设备列表包装(附汇总) */
    public record EdgeDeviceListResponse(EdgeSummary summary, List<EdgeDeviceResponse> devices) {}
}
