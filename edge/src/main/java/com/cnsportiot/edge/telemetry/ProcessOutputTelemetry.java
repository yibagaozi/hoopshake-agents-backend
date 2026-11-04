package com.cnsportiot.edge.telemetry;

import com.cnsportiot.edge.config.EdgeProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProcessOutputTelemetry {

    private static final Pattern FINAL_EXCEPTION = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.]*):\\s*(.*)$");
    private static final UUID NO_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final TelemetryCollector collector;
    private final EdgeProperties properties;
    private final ConcurrentMap<UUID, StringBuilder> tracebacks = new ConcurrentHashMap<>();

    public ProcessOutputTelemetry(TelemetryCollector collector, EdgeProperties properties) {
        this.collector = collector;
        this.properties = properties;
    }

    public void line(String line, String processType, String processName,
                     UUID runId, Long pid, String sessionId, String source) {
        if (!properties.getTelemetry().isEnabled()) {
            return;
        }
        String value = line == null ? "" : line;
        UUID traceKey = traceKey(runId);
        StringBuilder traceback = tracebacks.get(traceKey);
        if (traceback != null) {
            traceback.append(value).append('\n');
            Matcher matcher = FINAL_EXCEPTION.matcher(value.trim());
            if (matcher.matches() || value.isBlank()) {
                flushTraceback(traceKey, traceback.toString(), processType, processName,
                        pid, sessionId, source);
            }
            return;
        }
        if (value.contains("Traceback") && properties.getTelemetry().isPythonLogsEnabled()) {
            tracebacks.put(traceKey, new StringBuilder(value).append('\n'));
            return;
        }

        String upper = value.toUpperCase();
        String level = upper.contains("ERROR") || upper.contains("CRITICAL") || value.contains("Traceback")
                ? "ERROR" : upper.contains("WARN") ? "WARN" : "INFO";
        if (!properties.getTelemetry().isPythonLogsEnabled() && !"ERROR".equals(level)) {
            return;
        }
        collector.log(TelemetryLogEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(OffsetDateTime.now())
                .level(level)
                .source(source)
                .processType(processType)
                .processName(processName)
                .runId(runId)
                .pid(pid)
                .sessionId(sessionId)
                .message(truncate(value, properties.getTelemetry().getMaxMessageChars()))
                .build());
    }

    public void finish(UUID runId) {
        finish(runId, "process", "process", "process");
    }

    public void finish(UUID runId, String processType, String processName, String source) {
        StringBuilder traceback = tracebacks.remove(traceKey(runId));
        if (traceback == null) {
            return;
        }
        String value = traceback.toString();
        String errorClass = null;
        String errorMessage = null;
        String[] lines = value.split("\\R");
        if (lines.length > 0) {
            Matcher matcher = FINAL_EXCEPTION.matcher(lines[lines.length - 1].trim());
            if (matcher.matches()) {
                errorClass = matcher.group(1);
                errorMessage = matcher.group(2);
            }
        }
        collector.log(TelemetryLogEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(OffsetDateTime.now())
                .level("ERROR")
                .source(source)
                .processType(processType)
                .processName(processName)
                .runId(runId)
                .message(truncate(value, properties.getTelemetry().getMaxMessageChars()))
                .errorClass(errorClass)
                .errorMessage(errorMessage)
                .stackTrace(truncate(value, properties.getTelemetry().getMaxStackChars()))
                .build());
    }

    private void flushTraceback(UUID runId, String traceback, String processType, String processName,
                                Long pid, String sessionId, String source) {
        tracebacks.remove(runId);
        String errorClass = null;
        String errorMessage = null;
        String[] lines = traceback.split("\\R");
        if (lines.length > 0) {
            Matcher matcher = FINAL_EXCEPTION.matcher(lines[lines.length - 1].trim());
            if (matcher.matches()) {
                errorClass = matcher.group(1);
                errorMessage = matcher.group(2);
            }
        }
        collector.log(TelemetryLogEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(OffsetDateTime.now())
                .level("ERROR")
                .source(source)
                .processType(processType)
                .processName(processName)
                .runId(runId)
                .pid(pid)
                .sessionId(sessionId)
                .message(truncate(traceback, properties.getTelemetry().getMaxMessageChars()))
                .errorClass(errorClass)
                .errorMessage(errorMessage)
                .stackTrace(truncate(traceback, properties.getTelemetry().getMaxStackChars()))
                .attrs(Map.of("aggregated", "python_traceback"))
                .build());
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static UUID traceKey(UUID runId) {
        return runId == null ? NO_RUN_ID : runId;
    }
}
