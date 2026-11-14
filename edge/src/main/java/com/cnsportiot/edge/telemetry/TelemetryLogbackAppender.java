package com.cnsportiot.edge.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.cnsportiot.edge.config.EdgeProperties;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class TelemetryLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final String OWN_PACKAGE = TelemetryLogbackAppender.class.getPackageName();

    private final TelemetryCollector collector;
    private final EdgeProperties.Telemetry config;

    public TelemetryLogbackAppender(TelemetryCollector collector, EdgeProperties.Telemetry config) {
        this.collector = collector;
        this.config = config;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!config.isJavaLogsEnabled()
                || event.getLoggerName() != null && event.getLoggerName().startsWith(OWN_PACKAGE)) {
            return;
        }
        Level minimum = Level.toLevel(config.getJavaLogLevel().toUpperCase(), Level.INFO);
        if (!event.getLevel().isGreaterOrEqual(minimum)) {
            return;
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        collector.log(TelemetryLogEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault()))
                .level(event.getLevel().toString())
                .source("java")
                .logger(event.getLoggerName())
                .message(truncate(event.getFormattedMessage(), config.getMaxMessageChars()))
                .errorClass(throwable == null ? null : throwable.getClassName())
                .errorMessage(throwable == null ? null : throwable.getMessage())
                .stackTrace(throwable == null ? null : stackTrace(throwable))
                .attrs(Map.of(
                        "thread", event.getThreadName(),
                        "marker", String.valueOf(event.getMarker())
                ))
                .build());
    }

    private String stackTrace(IThrowableProxy throwable) {
        if (throwable.getStackTraceElementProxyArray() == null) {
            return null;
        }
        String stack = Arrays.stream(throwable.getStackTraceElementProxyArray())
                .map(StackTraceElementProxy::toString)
                .collect(Collectors.joining("\n"));
        return truncate(stack, config.getMaxStackChars());
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
