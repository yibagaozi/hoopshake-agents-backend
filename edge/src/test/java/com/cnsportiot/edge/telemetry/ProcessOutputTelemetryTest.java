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
    void lineWithoutRunIdDoesNotFail() {
        EdgeProperties properties = new EdgeProperties();
        TelemetryCollector collector = new TelemetryCollector(properties);
        ProcessOutputTelemetry telemetry = new ProcessOutputTelemetry(collector, properties);

        telemetry.line("Traceback (most recent call last):", "cv", "cv", null, null, null, "python");
        telemetry.finish(null, "cv", "cv", "python");

        assertThat(collector.drain(10)).singleElement().isInstanceOf(TelemetryLogEvent.class);
    }
}
