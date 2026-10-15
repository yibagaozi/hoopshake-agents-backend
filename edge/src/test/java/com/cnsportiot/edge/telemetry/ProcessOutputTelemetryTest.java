package com.cnsportiot.edge.telemetry;

import com.cnsportiot.edge.config.EdgeProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessOutputTelemetryTest {

    @Test
    void aggregatesPythonTracebackIntoStructuredException() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);
        UUID runId = UUID.randomUUID();

        telemetry.line("Traceback (most recent call last):", "cv", "cv", runId, 1L, "s", "python");
        telemetry.line("  File \"main.py\", line 10, in run", "cv", "cv", runId, 1L, "s", "python");
        telemetry.line("ValueError: camera not found", "cv", "cv", runId, 1L, "s", "python");

        List<Object> events = collector.drain(10);
        assertThat(events).singleElement().isInstanceOf(TelemetryLogEvent.class);
        TelemetryLogEvent event = (TelemetryLogEvent) events.get(0);
        assertThat(event.getLevel()).isEqualTo("ERROR");
        assertThat(event.getErrorClass()).isEqualTo("ValueError");
        assertThat(event.getErrorMessage()).isEqualTo("camera not found");
        assertThat(event.getStackTrace()).contains("main.py");
    }

    @Test
    void normalPythonLineKeepsLevelAndContext() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);
        UUID runId = UUID.randomUUID();

        telemetry.line("WARNING: frame dropped", "batch", "python.exe", runId, 2L, "s", "python");

        List<Object> events = collector.drain(10);
        assertThat(events).singleElement().isInstanceOf(TelemetryLogEvent.class);
        TelemetryLogEvent event = (TelemetryLogEvent) events.get(0);
        assertThat(event.getLevel()).isEqualTo("WARN");
        assertThat(event.getProcessType()).isEqualTo("batch");
        assertThat(event.getMessage()).isEqualTo("WARNING: frame dropped");
    }

    @Test
    void finishFlushesUnfinishedTracebackWithProcessContext() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);
        UUID runId = UUID.randomUUID();

        telemetry.line("Traceback (most recent call last):", "batch", "run_session.py",
                runId, 2L, "s", "python");
        telemetry.finish(runId, "batch", "run_session.py", "python");

        List<Object> events = collector.drain(10);
        assertThat(events).singleElement().isInstanceOf(TelemetryLogEvent.class);
        TelemetryLogEvent event = (TelemetryLogEvent) events.get(0);
        assertThat(event.getProcessType()).isEqualTo("batch");
        assertThat(event.getProcessName()).isEqualTo("run_session.py");
        assertThat(event.getSource()).isEqualTo("python");
        assertThat(event.getStackTrace()).contains("Traceback");
    }

    @Test
    void cvMetricsLineBecomesMetricEvents() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);

        telemetry.line("[cv-metrics] {\"schema\":1,\"ts_ms\":1760000000000.0,\"session_id\":\"lesson-1\","
                + "\"interval_s\":10.0,\"metrics\":["
                + "{\"name\":\"cv.live.process.fps\",\"value\":7.5,\"unit\":\"fps\",\"dims\":{\"camera\":\"cam_03\"}},"
                + "{\"name\":\"cv.live.identity.ratio\",\"value\":0.8,\"unit\":\"ratio\","
                + "\"dims\":{\"camera\":\"cam_01\",\"conf\":\"high\"}},"
                + "{\"name\":\"cv.live.students.visible\",\"value\":12,\"unit\":\"count\",\"dims\":{}},"
                + "{\"name\":\"bad\",\"value\":null}]}",
                "cv", "cv", UUID.randomUUID(), 3L, "lesson-1", "python");

        List<Object> events = collector.drain(10);
        assertThat(events).hasSize(3).allMatch(e -> e instanceof TelemetryMetricEvent);
        TelemetryMetricEvent fps = (TelemetryMetricEvent) events.get(0);
        assertThat(fps.getMetricName()).isEqualTo("cv.live.process.fps");
        assertThat(fps.getValue()).isEqualTo(7.5);
        assertThat(fps.getUnit()).isEqualTo("fps");
        assertThat(fps.getCameraId()).isEqualTo("cam_03");
        assertThat(fps.getSessionId()).isEqualTo("lesson-1");
        assertThat(fps.getProcessType()).isEqualTo("cv");
        assertThat(fps.getDims()).isNull();
        assertThat(fps.getOccurredAt().toInstant().toEpochMilli()).isEqualTo(1_760_000_000_000L);
        TelemetryMetricEvent conf = (TelemetryMetricEvent) events.get(1);
        assertThat(conf.getCameraId()).isEqualTo("cam_01");
        assertThat(conf.getDims()).containsEntry("conf", "high");
        assertThat(((TelemetryMetricEvent) events.get(2)).getCameraId()).isNull();
    }

    @Test
    void cvEventLineBecomesStructuredLog() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);

        telemetry.line("[cv-event] {\"schema\":1,\"ts_ms\":1760000000000.0,\"session_id\":\"lesson-1\","
                + "\"event\":\"action_emitted\",\"action_id\":\"6720a1b3-00012\",\"student_id\":\"stu_03\","
                + "\"made\":true,\"angle_rows\":24,\"emit_latency_ms\":1450.5,\"phases\":[1,2]}",
                "cv", "cv", UUID.randomUUID(), 3L, "lesson-1", "python");
        telemetry.line("[cv-event] {\"schema\":1,\"event\":\"identity_switch\",\"camera\":\"cam_02\","
                + "\"from_student\":\"stu_01\",\"to_student\":\"stu_04\"}", "cv", "cv", null, null, null, "python");
        telemetry.line("[cv-event] {\"schema\":1,\"event\":\"detect_error\",\"error\":\"x\"}",
                "cv", "cv", null, null, null, "python");

        List<Object> events = collector.drain(10);
        assertThat(events).hasSize(3).allMatch(e -> e instanceof TelemetryLogEvent);
        TelemetryLogEvent action = (TelemetryLogEvent) events.get(0);
        assertThat(action.getLevel()).isEqualTo("INFO");
        assertThat(action.getOperation()).isEqualTo("action_emitted");
        assertThat(action.getLogger()).isEqualTo("cv-event");
        assertThat(action.getSessionId()).isEqualTo("lesson-1");
        assertThat(action.getAttrs())
                .containsEntry("action_id", "6720a1b3-00012")
                .containsEntry("made", true)
                .containsEntry("angle_rows", 24L)
                .containsEntry("emit_latency_ms", 1450.5)
                .containsEntry("phases", "[1,2]")
                .doesNotContainKeys("schema", "ts_ms", "event");
        TelemetryLogEvent sw = (TelemetryLogEvent) events.get(1);
        assertThat(sw.getLevel()).isEqualTo("WARN");
        assertThat(sw.getCameraId()).isEqualTo("cam_02");
        assertThat(((TelemetryLogEvent) events.get(2)).getLevel()).isEqualTo("ERROR");
    }

    @Test
    void malformedOrDisabledCvLineFallsBackToPlainLog() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);

        telemetry.line("[cv-metrics] {not json", "cv", "cv", null, null, null, "python");
        properties.getTelemetry().setCvStructuredEnabled(false);
        telemetry.line("[cv-event] {\"event\":\"live_start\"}", "cv", "cv", null, null, null, "python");

        List<Object> events = collector.drain(10);
        assertThat(events).hasSize(2).allMatch(e -> e instanceof TelemetryLogEvent);
        assertThat(((TelemetryLogEvent) events.get(0)).getMessage()).startsWith("[cv-metrics]");
        assertThat(((TelemetryLogEvent) events.get(1)).getOperation()).isNull();
    }

    @Test
    void cvLineDuringTracebackIsNotSwallowed() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);
        UUID runId = UUID.randomUUID();

        telemetry.line("Traceback (most recent call last):", "cv", "cv", runId, 1L, "s", "python");
        telemetry.line("[cv-event] {\"event\":\"ws_connected\",\"connects\":2}", "cv", "cv", runId, 1L, "s", "python");
        telemetry.line("ValueError: boom", "cv", "cv", runId, 1L, "s", "python");

        List<Object> events = collector.drain(10);
        assertThat(events).hasSize(2);
        assertThat(((TelemetryLogEvent) events.get(0)).getOperation()).isEqualTo("ws_connected");
        assertThat(((TelemetryLogEvent) events.get(1)).getErrorClass()).isEqualTo("ValueError");
        assertThat(((TelemetryLogEvent) events.get(1)).getStackTrace()).doesNotContain("cv-event");
    }

    @Test
    void lineWithoutRunIdDoesNotFail() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);

        telemetry.line("Traceback (most recent call last):", "cv", "cv", null, null, null, "python");
        telemetry.finish(null, "cv", "cv", "python");

        assertThat(collector.drain(10)).singleElement().isInstanceOf(TelemetryLogEvent.class);
    }
}
