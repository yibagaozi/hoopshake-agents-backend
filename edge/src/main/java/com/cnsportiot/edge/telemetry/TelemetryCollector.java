package com.cnsportiot.edge.telemetry;

import com.cnsportiot.edge.config.EdgeProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TelemetryCollector {

    private final EdgeProperties properties;
    private final LinkedBlockingQueue<Object> queue;
    private final AtomicLong droppedCount = new AtomicLong();

    public TelemetryCollector(EdgeProperties properties) {
        this.properties = properties;
        this.queue = new LinkedBlockingQueue<>(Math.max(1024, properties.getTelemetry().getQueueCapacity()));
    }

    public boolean enabled() {
        return properties.getTelemetry().isEnabled();
    }

    public void log(TelemetryLogEvent event) {
        if (!enabled()) {
            return;
        }
        offer(event);
    }

    public void metric(String name, double value, String unit, Map<String, Object> dims) {
        if (!enabled()) {
            return;
        }
        offer(TelemetryMetricEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(OffsetDateTime.now())
                .metricName(name)
                .value(value)
                .unit(unit)
                .dims(dims)
                .build());
    }

    public void run(TelemetryRunEvent event) {
        if (!enabled()) {
            return;
        }
        offer(event);
    }

    public List<Object> drain(int max) {
        int limit = Math.max(1, Math.min(Math.min(max, properties.getTelemetry().getBatchSize()), 1000));
        List<Object> events = new ArrayList<>(Math.min(limit, 1000));
        queue.drainTo(events, limit);
        return events;
    }

    public void requeue(List<Object> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (Object event : events) {
            offer(event);
        }
    }

    public int pendingCount() {
        return queue.size();
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    private void offer(Object event) {
        while (!queue.offer(event)) {
            Object dropped = queue.poll();
            if (dropped == null) {
                continue;
            }
            droppedCount.incrementAndGet();
        }
    }
}
