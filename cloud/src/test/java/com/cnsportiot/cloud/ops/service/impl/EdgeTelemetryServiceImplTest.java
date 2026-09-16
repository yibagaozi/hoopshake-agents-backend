package com.cnsportiot.cloud.ops.service.impl;

import com.cnsportiot.cloud.ops.dto.EdgeTelemetryAck;
import com.cnsportiot.cloud.ops.dto.EdgeTelemetryRequest;
import com.cnsportiot.cloud.ops.entity.EdgeProcessRun;
import com.cnsportiot.cloud.ops.entity.EdgeTelemetryLog;
import com.cnsportiot.cloud.ops.entity.EdgeTelemetryMetric;
import com.cnsportiot.cloud.ops.repository.EdgeProcessRunRepository;
import com.cnsportiot.cloud.ops.repository.EdgeTelemetryLogRepository;
import com.cnsportiot.cloud.ops.repository.EdgeTelemetryMetricRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeTelemetryServiceImplTest {

    private FakeRepository<EdgeTelemetryLog> logStore;
    private FakeRepository<EdgeTelemetryMetric> metricStore;
    private FakeRepository<EdgeProcessRun> runStore;
    private EdgeTelemetryServiceImpl service;

    @BeforeEach
    void setup() {
        logStore = new FakeRepository<>();
        metricStore = new FakeRepository<>();
        runStore = new FakeRepository<>();
        service = new EdgeTelemetryServiceImpl(
                logStore.proxy(EdgeTelemetryLogRepository.class),
                metricStore.proxy(EdgeTelemetryMetricRepository.class),
                runStore.proxy(EdgeProcessRunRepository.class));
    }

    @Test
    void ingest_savesLogMetricAndRun() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        EdgeTelemetryRequest request = new EdgeTelemetryRequest(
                "edge-01", now,
                List.of(new EdgeTelemetryRequest.LogItem(
                        "log-1", now, "ERROR", "python", null, "boom",
                        "ValueError", "bad", "trace", "cv", "cv", runId,
                        123L, "lesson-1", null, "cam_01", null, null)),
                List.of(new EdgeTelemetryRequest.MetricItem(
                        "metric-1", now, "cv.ws.received.total", 10.0, "count",
                        "lesson-1", null, null, "cv", "cv", null)),
                List.of(new EdgeTelemetryRequest.RunItem(
                        runId, "cv", "cv", "python run", null, now, now,
                        100L, 123L, 0, "SUCCEEDED", null, null, null)));

        EdgeTelemetryAck ack = service.ingest(request);

        assertThat(ack.acceptedLogs()).isEqualTo(1);
        assertThat(ack.acceptedMetrics()).isEqualTo(1);
        assertThat(ack.acceptedRuns()).isEqualTo(1);
        assertThat(logStore.saved).singleElement().satisfies(entity -> {
            assertThat(entity.getEdgeId()).isEqualTo("edge-01");
            assertThat(entity.getLevel()).isEqualTo("ERROR");
            assertThat(entity.getSource()).isEqualTo("PYTHON");
            assertThat(entity.getAttrs()).isEmpty();
        });
        assertThat(metricStore.saved).singleElement().satisfies(entity -> {
            assertThat(entity.getMetricName()).isEqualTo("cv.ws.received.total");
            assertThat(entity.getValue()).isEqualTo(10.0);
            assertThat(entity.getDims()).isEmpty();
        });
        assertThat(runStore.saved).singleElement().satisfies(entity -> {
            assertThat(entity.getRunId()).isEqualTo(runId);
            assertThat(entity.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(entity.getAttrs()).isEmpty();
        });
    }

    @Test
    void ingest_isIdempotentByEventAndRunId() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        EdgeTelemetryLog existingLog = EdgeTelemetryLog.builder().eventId("log-1").build();
        EdgeTelemetryMetric existingMetric = EdgeTelemetryMetric.builder().eventId("metric-1").build();
        EdgeProcessRun existingRun = EdgeProcessRun.builder().runId(runId).build();
        logStore.put("log-1", existingLog);
        metricStore.put("metric-1", existingMetric);
        runStore.put(runId, existingRun);

        EdgeTelemetryAck ack = service.ingest(new EdgeTelemetryRequest(
                "edge-01", now,
                List.of(new EdgeTelemetryRequest.LogItem(
                        "log-1", now, "ERROR", "java", null, "boom", null, null, null,
                        null, null, null, null, null, null, null, null, null)),
                List.of(new EdgeTelemetryRequest.MetricItem(
                        "metric-1", now, "metric", 1.0, null, null, null, null, null, null, null)),
                List.of(new EdgeTelemetryRequest.RunItem(
                        runId, "cv", "cv", null, null, now, null, null,
                        null, null, "RUNNING", null, null, null))));

        assertThat(ack.duplicatedLogs()).isEqualTo(1);
        assertThat(ack.duplicatedMetrics()).isEqualTo(1);
        assertThat(ack.updatedRuns()).isEqualTo(1);
        assertThat(ack.acceptedLogs()).isZero();
        assertThat(ack.acceptedMetrics()).isZero();
        assertThat(ack.acceptedRuns()).isZero();
        assertThat(existingLog.getMessage()).isEqualTo("boom");
        assertThat(existingMetric.getValue()).isEqualTo(1.0);
        assertThat(existingRun.getStatus()).isEqualTo("RUNNING");
    }

    private static final class FakeRepository<T> {
        private final Map<Object, T> entities = new HashMap<>();
        private final List<T> saved = new ArrayList<>();

        private void put(Object key, T entity) {
            entities.put(key, entity);
        }

        @SuppressWarnings("unchecked")
        private <R> R proxy(Class<R> type) {
            return (R) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("findByEventId".equals(name) || "findByRunId".equals(name)) {
                            return Optional.ofNullable(entities.get(args[0]));
                        }
                        if ("saveAll".equals(name)) {
                            for (Object entity : (Iterable<?>) args[0]) {
                                saved.add((T) entity);
                            }
                            return args[0];
                        }
                        if ("save".equals(name)) {
                            saved.add((T) args[0]);
                            return args[0];
                        }
                        if ("toString".equals(name)) {
                            return "FakeRepository[" + type.getSimpleName() + "]";
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException(method.toString());
                    });
        }
    }
}
