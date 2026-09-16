package com.cnsportiot.edge.telemetry;

import com.cnsportiot.edge.cloudsync.CloudIngestClient;
import com.cnsportiot.edge.config.EdgeProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeTelemetryUploaderTest {

    @Test
    void flush_batchesLogsMetricsAndRuns() {
        EdgeProperties properties = new EdgeProperties();
        properties.getTelemetry().setBatchSize(10);
        TelemetryCollector collector = new TelemetryCollector(properties);
        RecordingCloudClient cloudClient = new RecordingCloudClient(properties);
        EdgeTelemetryUploader uploader = new EdgeTelemetryUploader(properties, collector, cloudClient);
        UUID runId = UUID.randomUUID();

        collector.log(TelemetryLogEvent.builder()
                .eventId("log-1").occurredAt(OffsetDateTime.now())
                .level("ERROR").source("python").message("boom").build());
        collector.metric("cv.ws.received.total", 3, "count", null);
        collector.run(TelemetryRunEvent.builder()
                .runId(runId).processType("cv").processName("cv")
                .startedAt(OffsetDateTime.now()).status("RUNNING").build());

        uploader.flush();

        Map<String, Object> body = cloudClient.body;
        assertThat(body.get("edgeId")).isEqualTo("edge-01");
        assertThat((List<?>) body.get("logs")).hasSize(1);
        assertThat((List<?>) body.get("metrics")).hasSize(1);
        assertThat((List<?>) body.get("runs")).hasSize(1);
        assertThat(collector.pendingCount()).isZero();
    }

    @Test
    void flush_failureKeepsEventsForRetry() {
        EdgeProperties properties = new EdgeProperties();
        properties.getTelemetry().setBatchSize(10);
        TelemetryCollector collector = new TelemetryCollector(properties);
        RecordingCloudClient cloudClient = new RecordingCloudClient(properties);
        cloudClient.failure = new RuntimeException("offline");
        EdgeTelemetryUploader uploader = new EdgeTelemetryUploader(properties, collector, cloudClient);
        collector.metric("metric", 1, null, null);

        uploader.flush();

        assertThat(collector.pendingCount()).isEqualTo(1);
    }

    private static final class RecordingCloudClient extends CloudIngestClient {
        private Map<String, Object> body;
        private RuntimeException failure;

        private RecordingCloudClient(EdgeProperties properties) {
            super(properties);
        }

        @Override
        public void pushEdgeTelemetry(Map<String, Object> body) {
            if (failure != null) {
                throw failure;
            }
            this.body = body;
        }
    }
}
