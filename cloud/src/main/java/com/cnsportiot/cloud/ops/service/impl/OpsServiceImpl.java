package com.cnsportiot.cloud.ops.service.impl;

import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.ratelimit.LlmStreamBulkhead;
import com.cnsportiot.cloud.harness.ratelimit.TokenBucketRateLimiter;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.dto.OpsDtos.*;
import com.cnsportiot.cloud.ops.entity.EdgeDevice;
import com.cnsportiot.cloud.ops.evaluator.EdgeHealthEvaluator;
import com.cnsportiot.cloud.ops.repository.EdgeDeviceRepository;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运维聚合实现。业务量走各仓储 count;Agent 表现走 chat_message.detail 近窗 jsonb 聚合;
 * 系统健康读进程内韧性 bean;边缘态由 {@link EdgeHealthEvaluator} 派生
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsServiceImpl implements OpsService {

    private final StudentRepository studentRepo;
    private final AccountRepository accountRepo;
    private final LessonRepository lessonRepo;
    private final TrainingSessionRepository trainingSessionRepo;
    private final ActionClipRepository actionClipRepo;
    private final InstantFeedbackRepository instantFeedbackRepo;
    private final ChatSessionRepository chatSessionRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final KnowledgeDocumentRepository knowledgeDocumentRepo;
    private final EdgeDeviceRepository edgeDeviceRepo;

    private final LlmGateway llmGateway;
    private final LlmStreamBulkhead bulkhead;
    private final TokenBucketRateLimiter askRateLimiter;
    private final EdgeHealthEvaluator healthEvaluator;
    private final OpsProperties props;

    @Override
    @Transactional(readOnly = true)
    public OverviewResponse overview(int agentWindowHours) {
        EdgeDeviceListResponse edge = edgeDevices(null);
        return new OverviewResponse(
                OffsetDateTime.now(),
                business(),
                agentQuality(agentWindowHours),
                systemHealth(),
                edge.summary());
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessResponse business() {
        LessonCounts lessons = new LessonCounts(
                lessonRepo.count(),
                lessonRepo.countByStatus(LessonStatus.PLANNED),
                lessonRepo.countByStatus(LessonStatus.ONGOING),
                lessonRepo.countByStatus(LessonStatus.FINISHED));
        DataVolume data = new DataVolume(
                actionClipRepo.count(),
                instantFeedbackRepo.count(),
                chatSessionRepo.count(),
                chatMessageRepo.count(),
                knowledgeDocumentRepo.count());
        return new BusinessResponse(
                studentRepo.count(),
                accountRepo.countByRole(Role.TEACHER),
                lessons,
                trainingSessionRepo.count(),
                data);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentQualityResponse agentQuality(int windowHours) {
        int win = windowHours > 0 ? windowHours : props.getAgent().getDefaultWindowHours();
        OffsetDateTime since = OffsetDateTime.now().minusHours(win);
        try {
            List<Object[]> rows = chatMessageRepo.aggregateAgentQualitySince(since);
            Object[] r = (rows == null || rows.isEmpty()) ? null : rows.get(0);
            long answered = lng(r, 0);
            long degraded = lng(r, 1);
            long ragHit = lng(r, 2);
            Double avgChars = dbl(r, 3);
            long toolOk = lng(r, 4);
            long toolDeny = lng(r, 5);
            long toolError = lng(r, 6);
            long toolTotal = toolOk + toolDeny + toolError;
            return new AgentQualityResponse(
                    win, answered, degraded,
                    rate(degraded, answered),
                    ragHit, rate(ragHit, answered),
                    avgChars,
                    new ToolCounts(toolOk, toolDeny, toolError),
                    rate(toolError, toolTotal));
        } catch (RuntimeException e) {
            log.warn("Agent 表现聚合失败,降级返回空 window={}h: {}", win, e.toString());
            return new AgentQualityResponse(win, 0, 0, null, 0, null, null,
                    new ToolCounts(0, 0, 0), null);
        }
    }

    @Override
    public SystemHealthResponse systemHealth() {
        java.util.OptionalInt cs = llmGateway.circuitState();
        String circuit = cs.isPresent() ? circuitName(cs.getAsInt()) : null;
        return new SystemHealthResponse(
                llmGateway.isEnabled(),
                new CircuitSnapshot(circuit),
                new StreamSnapshot(bulkhead.active(), bulkhead.available(),
                        bulkhead.maxPermits(), bulkhead.rejectedCount()),
                new RateLimitSnapshot(askRateLimiter.rejectedCount(), askRateLimiter.trackedKeys()));
    }

    @Override
    @Transactional(readOnly = true)
    public EdgeDeviceListResponse edgeDevices(EdgeHealth filter) {
        List<EdgeDevice> all = edgeDeviceRepo.findAllByOrderByLastSeenAtDesc();
        long online = 0, stale = 0, offline = 0;
        List<EdgeDeviceResponse> out = new java.util.ArrayList<>(all.size());
        for (EdgeDevice d : all) {
            EdgeHealth h = healthEvaluator.evaluate(d.getLastSeenAt());
            switch (h) {
                case ONLINE -> online++;
                case STALE -> stale++;
                case OFFLINE -> offline++;
            }
            if (filter == null || filter == h) {
                out.add(toDto(d, h));
            }
        }
        return new EdgeDeviceListResponse(
                new EdgeSummary(all.size(), online, stale, offline), out);
    }

    @Override
    @Transactional(readOnly = true)
    public EdgeDeviceResponse edgeDevice(String deviceId) {
        return edgeDeviceRepo.findByDeviceId(deviceId)
                .map(d -> toDto(d, healthEvaluator.evaluate(d.getLastSeenAt())))
                .orElse(null);
    }

    // 辅助

    private static EdgeDeviceResponse toDto(EdgeDevice d, EdgeHealth h) {
        return new EdgeDeviceResponse(
                d.getDeviceId(), d.getName(), d.getCourtId(), d.getReportedStatus(), h,
                d.getAppVersion(), d.getFirmware(), d.getIpAddress(),
                d.getMetrics(), d.getLastError(), d.getLastSeenAt());
    }

    private static String circuitName(int code) {
        return switch (code) {
            case 0 -> "CLOSED";
            case 1 -> "HALF_OPEN";
            case 2 -> "OPEN";
            default -> null;
        };
    }

    /** 比率:分母 0 → null(前端显示 n/a),否则四舍五入到 3 位小数 */
    private static Double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 1000.0) / 1000.0;
    }

    private static long lng(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) {
            return 0L;
        }
        return ((Number) row[i]).longValue();
    }

    private static Double dbl(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) {
            return null;
        }
        return Math.round(((Number) row[i]).doubleValue() * 10.0) / 10.0;
    }
}
