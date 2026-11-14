package com.cnsportiot.cloud.ops.service.impl;

import com.cnsportiot.cloud.ops.dto.EdgeTelemetryAck;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryRequest;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeProcessRunResponse;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeTelemetryLogResponse;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeTelemetryMetricResponse;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryResponses.EdgeTelemetrySummaryResponse;
import com.cnsportiot.cloud.ops.entity.EdgeProcessRun;
import com.cnsportiot.cloud.ops.entity.EdgeTelemetryLog;
import com.cnsportiot.cloud.ops.entity.EdgeTelemetryMetric;
import com.cnsportiot.cloud.ops.repository.EdgeProcessRunRepository;
import com.cnsportiot.cloud.ops.repository.EdgeTelemetryLogRepository;
import com.cnsportiot.cloud.ops.repository.EdgeTelemetryMetricRepository;
import com.cnsportiot.cloud.ops.service.EdgeTelemetryService;
import com.cnsportiot.contracts.common.PageResponse;
import com.cnsportiot.cloud.common.PageResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EdgeTelemetryServiceImpl implements EdgeTelemetryService {

    private final EdgeTelemetryLogRepository logRepository;
    private final EdgeTelemetryMetricRepository metricRepository;
    private final EdgeProcessRunRepository runRepository;

    @Override
    @Transactional
    public EdgeTelemetryAck ingest(EdgeTelemetryRequest request) {
        List<EdgeTelemetryLog> logs = new ArrayList<>();
        int duplicatedLogs = 0;
        if (request.logs() != null) {
            for (var item : request.logs()) {
                EdgeTelemetryLog entity = logRepository.findByEventId(item.eventId()).orElse(null);
                if (entity == null) {
                    entity = EdgeTelemetryLog.builder().eventId(item.eventId()).build();
                } else {
                    duplicatedLogs++;
                }
                apply(entity, request.edgeId(), item);
                logs.add(entity);
            }
        }
        logRepository.saveAll(logs);

        List<EdgeTelemetryMetric> metrics = new ArrayList<>();
        int duplicatedMetrics = 0;
        if (request.metrics() != null) {
            for (var item : request.metrics()) {
                EdgeTelemetryMetric entity = metricRepository.findByEventId(item.eventId()).orElse(null);
                if (entity == null) {
                    entity = EdgeTelemetryMetric.builder().eventId(item.eventId()).build();
                } else {
                    duplicatedMetrics++;
                }
                apply(entity, request.edgeId(), item);
                metrics.add(entity);
            }
        }
        metricRepository.saveAll(metrics);

        int updatedRuns = 0;
        List<EdgeProcessRun> runs = new ArrayList<>();
        if (request.runs() != null) {
            for (var item : request.runs()) {
                EdgeProcessRun entity = runRepository.findByRunId(item.runId()).orElse(null);
                if (entity == null) {
                    entity = EdgeProcessRun.builder().runId(item.runId()).build();
                } else {
                    updatedRuns++;
                }
                apply(entity, request.edgeId(), item);
                runs.add(entity);
            }
        }
        runRepository.saveAll(runs);

        return new EdgeTelemetryAck(
                request.edgeId(),
                logs.size() - duplicatedLogs, duplicatedLogs,
                metrics.size() - duplicatedMetrics, duplicatedMetrics,
                runs.size() - updatedRuns, updatedRuns);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EdgeTelemetryLogResponse> logs(
            String edgeId, String level, String source, String processType, String processName,
            UUID runId, String sessionId, UUID lessonId, String cameraId, String keyword,
            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        return PageResponses.from(
                logRepository.findAll(
                        logSpec(edgeId, level, source, processType, processName, runId,
                                sessionId, lessonId, cameraId, keyword, from, to),
                        PageResponses.toPageable(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))),
                this::toLogDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EdgeTelemetryMetricResponse> metrics(
            String edgeId, String metricName, String sessionId, UUID lessonId, String cameraId,
            String processType, String processName, OffsetDateTime from, OffsetDateTime to,
            int page, int size) {
        return PageResponses.from(
                metricRepository.findAll(
                        metricSpec(edgeId, metricName, sessionId, lessonId, cameraId,
                                processType, processName, from, to),
                        PageResponses.toPageable(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))),
                this::toMetricDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EdgeProcessRunResponse> runs(
            String edgeId, String processType, String processName, String status,
            OffsetDateTime from, OffsetDateTime to, int page, int size) {
        return PageResponses.from(
                runRepository.findAll(
                        runSpec(edgeId, processType, processName, status, from, to),
                        PageResponses.toPageable(page, size, Sort.by(Sort.Direction.DESC, "startedAt"))),
                this::toRunDto);
    }

    @Override
    @Transactional(readOnly = true)
    public EdgeTelemetrySummaryResponse summary(String edgeId, int windowHours) {
        int window = Math.max(windowHours, 1);
        OffsetDateTime since = OffsetDateTime.now().minusHours(window);
        Specification<EdgeTelemetryLog> logSpec = logSpec(edgeId, null, null, null, null,
                null, null, null, null, null, since, null);
        Specification<EdgeTelemetryMetric> metricSpec = metricSpec(edgeId, null, null,
                null, null, null, null, since, null);
        Specification<EdgeProcessRun> runSpec = runSpec(edgeId, null, null, null, since, null);

        List<EdgeProcessRun> latestRuns = runRepository.findAll(runSpec,
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "startedAt"))).getContent();
        List<EdgeTelemetryLog> latestErrors = logRepository.findAll(
                logSpec.and((root, query, cb) -> cb.equal(root.get("level"), "ERROR")),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "occurredAt"))).getContent();

        return new EdgeTelemetrySummaryResponse(
                OffsetDateTime.now(), since, blankToNull(edgeId),
                logRepository.count(logSpec),
                logRepository.count(logSpec.and((root, query, cb) -> cb.equal(root.get("level"), "ERROR"))),
                logRepository.count(logSpec.and((root, query, cb) -> cb.equal(root.get("level"), "WARN"))),
                logRepository.count(logSpec.and((root, query, cb) -> cb.equal(root.get("level"), "INFO"))),
                metricRepository.count(metricSpec),
                runRepository.count(runSpec),
                runRepository.count(runSpec.and((root, query, cb) -> cb.equal(root.get("status"), "RUNNING"))),
                runRepository.count(runSpec.and((root, query, cb) -> cb.equal(root.get("status"), "FAILED"))),
                latestRuns.stream().map(this::toRunDto).toList(),
                latestErrors.stream().map(this::toLogDto).toList());
    }

    private Specification<EdgeTelemetryLog> logSpec(
            String edgeId, String level, String source, String processType, String processName,
            UUID runId, String sessionId, UUID lessonId, String cameraId, String keyword,
            OffsetDateTime from, OffsetDateTime to) {
        String search = blankToNull(keyword);
        String lowerSearch = search == null ? null : search.toLowerCase();
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (blankToNull(edgeId) != null) predicates.add(cb.equal(root.get("edgeId"), edgeId));
            if (blankToNull(level) != null) predicates.add(cb.equal(root.get("level"), level.trim().toUpperCase()));
            if (blankToNull(source) != null) predicates.add(cb.equal(root.get("source"), source.trim().toUpperCase()));
            if (blankToNull(processType) != null) predicates.add(cb.equal(root.get("processType"), processType));
            if (blankToNull(processName) != null) predicates.add(cb.equal(root.get("processName"), processName));
            if (runId != null) predicates.add(cb.equal(root.get("runId"), runId));
            if (blankToNull(sessionId) != null) predicates.add(cb.equal(root.get("sessionId"), sessionId));
            if (lessonId != null) predicates.add(cb.equal(root.get("lessonId"), lessonId));
            if (blankToNull(cameraId) != null) predicates.add(cb.equal(root.get("cameraId"), cameraId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            if (lowerSearch != null) {
                String pattern = "%" + lowerSearch + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("message")), pattern),
                        cb.like(cb.lower(root.get("logger")), pattern),
                        cb.like(cb.lower(root.get("errorClass")), pattern),
                        cb.like(cb.lower(root.get("errorMessage")), pattern)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private Specification<EdgeTelemetryMetric> metricSpec(
            String edgeId, String metricName, String sessionId, UUID lessonId, String cameraId,
            String processType, String processName, OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (blankToNull(edgeId) != null) predicates.add(cb.equal(root.get("edgeId"), edgeId));
            if (blankToNull(metricName) != null) predicates.add(cb.equal(root.get("metricName"), metricName));
            if (blankToNull(sessionId) != null) predicates.add(cb.equal(root.get("sessionId"), sessionId));
            if (lessonId != null) predicates.add(cb.equal(root.get("lessonId"), lessonId));
            if (blankToNull(cameraId) != null) predicates.add(cb.equal(root.get("cameraId"), cameraId));
            if (blankToNull(processType) != null) predicates.add(cb.equal(root.get("processType"), processType));
            if (blankToNull(processName) != null) predicates.add(cb.equal(root.get("processName"), processName));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private Specification<EdgeProcessRun> runSpec(
            String edgeId, String processType, String processName, String status,
            OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (blankToNull(edgeId) != null) predicates.add(cb.equal(root.get("edgeId"), edgeId));
            if (blankToNull(processType) != null) predicates.add(cb.equal(root.get("processType"), processType));
            if (blankToNull(processName) != null) predicates.add(cb.equal(root.get("processName"), processName));
            if (blankToNull(status) != null) predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("startedAt"), to));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private EdgeTelemetryLogResponse toLogDto(EdgeTelemetryLog entity) {
        return new EdgeTelemetryLogResponse(
                entity.getId(), entity.getEventId(), entity.getEdgeId(), entity.getOccurredAt(),
                entity.getLevel(), entity.getSource(), entity.getLogger(), entity.getMessage(),
                entity.getErrorClass(), entity.getErrorMessage(), entity.getStackTrace(),
                entity.getProcessType(), entity.getProcessName(), entity.getRunId(), entity.getPid(),
                entity.getSessionId(), entity.getLessonId(), entity.getCameraId(), entity.getOperation(),
                entity.getAttrs(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private EdgeTelemetryMetricResponse toMetricDto(EdgeTelemetryMetric entity) {
        return new EdgeTelemetryMetricResponse(
                entity.getId(), entity.getEventId(), entity.getEdgeId(), entity.getOccurredAt(),
                entity.getMetricName(), entity.getValue(), entity.getUnit(), entity.getSessionId(),
                entity.getLessonId(), entity.getCameraId(), entity.getProcessType(), entity.getProcessName(),
                entity.getDims(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private EdgeProcessRunResponse toRunDto(EdgeProcessRun entity) {
        return new EdgeProcessRunResponse(
                entity.getId(), entity.getRunId(), entity.getEdgeId(), entity.getProcessType(),
                entity.getProcessName(), entity.getCommand(), entity.getWorkDir(), entity.getStartedAt(),
                entity.getFinishedAt(), entity.getDurationMs(), entity.getPid(), entity.getExitCode(),
                entity.getStatus(), entity.getTimeoutMs(), entity.getRestartCount(), entity.getAttrs(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private void apply(EdgeTelemetryLog entity, String edgeId, EdgeTelemetryRequest.LogItem item) {
        entity.setEdgeId(edgeId);
        entity.setOccurredAt(item.occurredAt());
        entity.setLevel(normalize(item.level()));
        entity.setSource(normalize(item.source()));
        entity.setLogger(item.logger());
        entity.setMessage(item.message());
        entity.setErrorClass(item.errorClass());
        entity.setErrorMessage(item.errorMessage());
        entity.setStackTrace(item.stackTrace());
        entity.setProcessType(item.processType());
        entity.setProcessName(item.processName());
        entity.setRunId(item.runId());
        entity.setPid(item.pid());
        entity.setSessionId(item.sessionId());
        entity.setLessonId(item.lessonId());
        entity.setCameraId(item.cameraId());
        entity.setOperation(item.operation());
        entity.setAttrs(orEmpty(item.attrs()));
    }

    private void apply(EdgeTelemetryMetric entity, String edgeId, EdgeTelemetryRequest.MetricItem item) {
        entity.setEdgeId(edgeId);
        entity.setOccurredAt(item.occurredAt());
        entity.setMetricName(item.metricName());
        entity.setValue(item.value());
        entity.setUnit(item.unit());
        entity.setSessionId(item.sessionId());
        entity.setLessonId(item.lessonId());
        entity.setCameraId(item.cameraId());
        entity.setProcessType(item.processType());
        entity.setProcessName(item.processName());
        entity.setDims(orEmpty(item.dims()));
    }

    private void apply(EdgeProcessRun entity, String edgeId, EdgeTelemetryRequest.RunItem item) {
        entity.setEdgeId(edgeId);
        entity.setProcessType(item.processType());
        entity.setProcessName(item.processName());
        entity.setCommand(item.command());
        entity.setWorkDir(item.workDir());
        entity.setStartedAt(item.startedAt());
        entity.setFinishedAt(item.finishedAt());
        entity.setDurationMs(item.durationMs());
        entity.setPid(item.pid());
        entity.setExitCode(item.exitCode());
        entity.setStatus(normalize(item.status()));
        entity.setTimeoutMs(item.timeoutMs());
        entity.setRestartCount(item.restartCount());
        entity.setAttrs(orEmpty(item.attrs()));
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}
