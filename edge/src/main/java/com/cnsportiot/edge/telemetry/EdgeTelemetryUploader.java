package com.cnsportiot.edge.telemetry;

import com.cnsportiot.edge.cloudsync.CloudIngestClient;
import com.cnsportiot.edge.config.EdgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EdgeTelemetryUploader {

    private final EdgeProperties properties;
    private final TelemetryCollector collector;
    private final CloudIngestClient cloudClient;

    public EdgeTelemetryUploader(EdgeProperties properties,
                                 TelemetryCollector collector,
                                 CloudIngestClient cloudClient) {
        this.properties = properties;
        this.collector = collector;
        this.cloudClient = cloudClient;
    }

    @Scheduled(fixedDelayString = "${hoopshake.edge.telemetry.flush-interval:5s}")
    public void flush() {
        if (!properties.getTelemetry().isEnabled()) {
            return;
        }
        List<Object> events = collector.drain(properties.getTelemetry().getBatchSize());
        if (events.isEmpty()) {
            return;
        }
        try {
            cloudClient.pushEdgeTelemetry(toRequestBody(events));
        } catch (RuntimeException e) {
            collector.requeue(events);
            log.warn("场边遥测上报失败,已保留 {} 条待重试: {}", events.size(), e.getMessage());
        }
    }

    private Map<String, Object> toRequestBody(List<Object> events) {
        List<Map<String, Object>> logs = new ArrayList<>();
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Object event : events) {
            if (event instanceof TelemetryLogEvent value) {
                logs.add(toLog(value));
            } else if (event instanceof TelemetryMetricEvent value) {
                metrics.add(toMetric(value));
            } else if (event instanceof TelemetryRunEvent value) {
                runs.add(toRun(value));
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("edgeId", properties.getEdgeId());
        body.put("reportedAt", OffsetDateTime.now().toString());
        body.put("logs", logs);
        body.put("metrics", metrics);
        body.put("runs", runs);
        return body;
    }

    private Map<String, Object> toLog(TelemetryLogEvent event) {
        Map<String, Object> map = new HashMap<>();
        put(map, "eventId", event.getEventId());
        put(map, "occurredAt", event.getOccurredAt());
        put(map, "level", event.getLevel());
        put(map, "source", event.getSource());
        put(map, "logger", event.getLogger());
        put(map, "message", event.getMessage());
        put(map, "errorClass", event.getErrorClass());
        put(map, "errorMessage", event.getErrorMessage());
        put(map, "stackTrace", event.getStackTrace());
        put(map, "processType", event.getProcessType());
        put(map, "processName", event.getProcessName());
        put(map, "runId", event.getRunId());
        put(map, "pid", event.getPid());
        put(map, "sessionId", event.getSessionId());
        put(map, "lessonId", event.getLessonId());
        put(map, "cameraId", event.getCameraId());
        put(map, "operation", event.getOperation());
        put(map, "attrs", event.getAttrs());
        return map;
    }

    private Map<String, Object> toMetric(TelemetryMetricEvent event) {
        Map<String, Object> map = new HashMap<>();
        put(map, "eventId", event.getEventId());
        put(map, "occurredAt", event.getOccurredAt());
        put(map, "metricName", event.getMetricName());
        put(map, "value", event.getValue());
        put(map, "unit", event.getUnit());
        put(map, "sessionId", event.getSessionId());
        put(map, "lessonId", event.getLessonId());
        put(map, "cameraId", event.getCameraId());
        put(map, "processType", event.getProcessType());
        put(map, "processName", event.getProcessName());
        put(map, "dims", event.getDims());
        return map;
    }

    private Map<String, Object> toRun(TelemetryRunEvent event) {
        Map<String, Object> map = new HashMap<>();
        put(map, "runId", event.getRunId());
        put(map, "processType", event.getProcessType());
        put(map, "processName", event.getProcessName());
        put(map, "command", event.getCommand());
        put(map, "workDir", event.getWorkDir());
        put(map, "startedAt", event.getStartedAt());
        put(map, "finishedAt", event.getFinishedAt());
        put(map, "durationMs", event.getDurationMs());
        put(map, "pid", event.getPid());
        put(map, "exitCode", event.getExitCode());
        put(map, "status", event.getStatus());
        put(map, "timeoutMs", event.getTimeoutMs());
        put(map, "restartCount", event.getRestartCount());
        put(map, "attrs", event.getAttrs());
        return map;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
