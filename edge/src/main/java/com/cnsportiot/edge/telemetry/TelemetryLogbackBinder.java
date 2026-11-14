package com.cnsportiot.edge.telemetry;

import ch.qos.logback.classic.Logger;
import com.cnsportiot.edge.config.EdgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TelemetryLogbackBinder {

    private final TelemetryCollector collector;
    private final EdgeProperties properties;
    private TelemetryLogbackAppender appender;

    public TelemetryLogbackBinder(TelemetryCollector collector, EdgeProperties properties) {
        this.collector = collector;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.getTelemetry().isEnabled() || !properties.getTelemetry().isJavaLogsEnabled()) {
            return;
        }
        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new TelemetryLogbackAppender(collector, properties.getTelemetry());
        appender.setContext(context);
        appender.setName("EDGE_TELEMETRY");
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @PreDestroy
    public void stop() {
        if (appender != null) {
            appender.stop();
            ch.qos.logback.classic.LoggerContext context =
                    (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
    }
}
