package com.cnsportiot.edge.telemetry;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.CvProcessManager;
import com.cnsportiot.edge.realtime.CvChannelHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class EdgeTelemetryMetricsSampler {

    private final EdgeProperties properties;
    private final TelemetryCollector collector;
    private final CvProcessManager cvProcessManager;
    private final CvChannelHandler cvChannelHandler;

    public EdgeTelemetryMetricsSampler(EdgeProperties properties,
                                       TelemetryCollector collector,
                                       CvProcessManager cvProcessManager,
                                       CvChannelHandler cvChannelHandler) {
        this.properties = properties;
        this.collector = collector;
        this.cvProcessManager = cvProcessManager;
        this.cvChannelHandler = cvChannelHandler;
    }

    @Scheduled(fixedDelayString = "${hoopshake.edge.telemetry.metric-interval:15s}")
    public void sample() {
        if (!properties.getTelemetry().isEnabled()) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        collector.metric("jvm.heap.used.bytes", runtime.totalMemory() - runtime.freeMemory(), "bytes", null);
        collector.metric("jvm.heap.max.bytes", runtime.maxMemory(), "bytes", null);
        collector.metric("jvm.heap.total.bytes", runtime.totalMemory(), "bytes", null);
        collector.metric("jvm.free.bytes", runtime.freeMemory(), "bytes", null);
        collector.metric("jvm.threads.count",
                ManagementFactory.getThreadMXBean().getThreadCount(), "count", null);
        collector.metric("jvm.uptime.seconds",
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0, "s", null);

        com.sun.management.OperatingSystemMXBean os =
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        double processCpu = os.getProcessCpuLoad();
        if (processCpu >= 0) {
            collector.metric("process.cpu.ratio", processCpu * 100, "percent", null);
        }
        double systemLoad = os.getSystemLoadAverage();
        if (systemLoad >= 0) {
            collector.metric("system.load.average", systemLoad, "load", null);
        }

        File dataRoot = properties.getDataRoot() == null ? new File(".") : new File(properties.getDataRoot());
        collector.metric("disk.usable.bytes", dataRoot.getUsableSpace(), "bytes", null);
        collector.metric("disk.total.bytes", dataRoot.getTotalSpace(), "bytes", null);
        collector.metric("disk.free.ratio",
                dataRoot.getTotalSpace() == 0 ? 0
                        : dataRoot.getUsableSpace() * 100.0 / dataRoot.getTotalSpace(),
                "percent", null);

        collector.metric("telemetry.queue.pending", collector.pendingCount(), "count", null);
        collector.metric("telemetry.queue.dropped", collector.droppedCount(), "count", null);

        String session = cvProcessManager.currentSession();
        collector.metric("cv.process.running",
                cvProcessManager.state().name().equals("RUNNING") ? 1 : 0, "boolean",
                Map.of("session", session == null ? "" : session));
        Long pid = cvProcessManager.pid();
        if (pid != null) {
            collector.metric("cv.process.pid", pid, "pid",
                    Map.of("session", session == null ? "" : session));
        }
        collector.metric("cv.process.restart.count", cvProcessManager.restartCount(), "count",
                Map.of("session", session == null ? "" : session));

        collector.metric("cv.ws.connected", cvChannelHandler.connected() ? 1 : 0, "boolean", null);
        collector.metric("cv.ws.received.total", cvChannelHandler.receivedCount(), "count", null);
        collector.metric("cv.ws.gap.total", cvChannelHandler.gapCount(), "count", null);
        collector.metric("cv.ws.payload.bytes.total", cvChannelHandler.payloadBytes(), "bytes", null);
        collector.metric("cv.ws.parse.errors.total", cvChannelHandler.parseErrorCount(), "count", null);
        Instant lastEventAt = cvChannelHandler.lastEventAt();
        if (lastEventAt != null) {
            collector.metric("cv.ws.last.event.age.ms",
                    Duration.between(lastEventAt, Instant.now()).toMillis(), "ms", null);
        }
        cvChannelHandler.eventCounts().forEach((type, count) ->
                collector.metric("cv.ws.event.count", count, "count", Map.of("type", type)));
    }
}
