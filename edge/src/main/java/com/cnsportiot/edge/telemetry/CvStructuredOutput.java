package com.cnsportiot.edge.telemetry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 算法直播进程(v2.2.0+enroll.1.live.1 起)的结构化 stdout 行 → 遥测事件:
 * <ul>
 *   <li>{@code [cv-metrics] {json}}:每 10 s 一批指标(帧率、延迟、已识别比例、ID 切换…)→ 每项一条 {@link TelemetryMetricEvent},
 *       {@code dims.camera} 写入 cameraId;</li>
 *   <li>{@code [cv-event] {json}}:离散锚点(live_start、models_ready、action_emitted、identity_switch、ws_disconnected…)
 *       → {@link TelemetryLogEvent},operation = 事件名,attrs = 事件字段。</li>
 * </ul>
 * 字段约定见算法仓库 docs/实时推理观测与精度评估.md。解析失败返回 false,调用方按普通输出行处理。
 */
final class CvStructuredOutput {

    static final String METRICS_PREFIX = "[cv-metrics] ";
    static final String EVENT_PREFIX = "[cv-event] ";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ENVELOPE = Set.of("schema", "ts_ms", "session_id", "event");
    private static final Set<String> WARN_EVENTS = Set.of(
            "model_warning", "identity_switch", "ws_disconnected", "timeline_gap");

    private final TelemetryCollector collector;

    CvStructuredOutput(TelemetryCollector collector) {
        this.collector = collector;
    }

    static boolean isStructured(String line) {
        return line != null && (line.startsWith(METRICS_PREFIX) || line.startsWith(EVENT_PREFIX));
    }

    boolean accept(String line, String processType, String processName, UUID runId, Long pid,
                   String sessionId, String source, int maxMessageChars) {
        try {
            if (line.startsWith(METRICS_PREFIX)) {
                return metrics(JSON.readTree(line.substring(METRICS_PREFIX.length())),
                        processType, processName, sessionId);
            }
            if (line.startsWith(EVENT_PREFIX)) {
                String body = line.substring(EVENT_PREFIX.length());
                return event(JSON.readTree(body), body, processType, processName, runId, pid, sessionId,
                        source, maxMessageChars);
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private boolean metrics(JsonNode root, String processType, String processName, String sessionId) {
        JsonNode items = root.get("metrics");
        if (items == null || !items.isArray()) {
            return false;
        }
        OffsetDateTime at = occurredAt(root);
        String session = sessionId != null ? sessionId : text(root, "session_id");
        for (JsonNode m : items) {
            JsonNode name = m.get("name");
            JsonNode value = m.get("value");
            if (name == null || !name.isString() || value == null || !value.isNumber()) {
                continue;
            }
            Map<String, Object> dims = new LinkedHashMap<>();
            String camera = null;
            JsonNode d = m.get("dims");
            if (d != null && d.isObject()) {
                for (Map.Entry<String, JsonNode> e : d.properties()) {
                    if ("camera".equals(e.getKey())) {
                        camera = e.getValue().asString(null);
                    } else {
                        dims.put(e.getKey(), scalar(e.getValue()));
                    }
                }
            }
            collector.metric(TelemetryMetricEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .occurredAt(at)
                    .metricName(name.asString())
                    .value(value.asDouble())
                    .unit(text(m, "unit"))
                    .sessionId(session)
                    .cameraId(camera)
                    .processType(processType)
                    .processName(processName)
                    .dims(dims.isEmpty() ? null : dims)
                    .build());
        }
        return true;
    }

    private boolean event(JsonNode root, String body, String processType, String processName, UUID runId,
                          Long pid, String sessionId, String source, int maxMessageChars) {
        String name = text(root, "event");
        if (name == null || name.isBlank()) {
            return false;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!ENVELOPE.contains(e.getKey())) {
                attrs.put(e.getKey(), scalar(e.getValue()));
            }
        }
        collector.log(TelemetryLogEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(occurredAt(root))
                .level(level(name))
                .source(source)
                .logger("cv-event")
                .operation(name)
                .processType(processType)
                .processName(processName)
                .runId(runId)
                .pid(pid)
                .sessionId(sessionId != null ? sessionId : text(root, "session_id"))
                .cameraId(text(root, "camera"))
                .message(body.length() <= maxMessageChars ? body : body.substring(0, maxMessageChars))
                .attrs(attrs)
                .build());
        return true;
    }

    static String level(String event) {
        String e = event.toLowerCase(Locale.ROOT);
        if (e.endsWith("_error")) {
            return "ERROR";
        }
        return WARN_EVENTS.contains(e) ? "WARN" : "INFO";
    }

    private static OffsetDateTime occurredAt(JsonNode root) {
        JsonNode ts = root.get("ts_ms");
        if (ts != null && ts.isNumber()) {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ts.asLong()), ZoneId.systemDefault());
        }
        return OffsetDateTime.now();
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asString(null);
    }

    /** 标量原样;对象/数组转紧凑 JSON 字符串(attrs/dims 只放可直接展示的值)。 */
    private static Object scalar(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isString()) {
            return v.asString();
        }
        return v.toString();
    }
}
