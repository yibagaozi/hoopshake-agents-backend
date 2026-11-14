package com.cnsportiot.cloud.ops.evaluator;

import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeHealth;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * 由 {@code last_seen_at} 与阈值派生设备健康态(读时计算,不落库)
 * 时钟可注入以便确定性单测
 */
@Component
public class EdgeHealthEvaluator {

    private final OpsProperties props;
    private final Supplier<OffsetDateTime> clock;

    public EdgeHealthEvaluator(){
        this.props = new OpsProperties();
        this.clock = OffsetDateTime::now;
    }

    public EdgeHealthEvaluator(OpsProperties props) {
        this(props, OffsetDateTime::now);
    }

    public EdgeHealthEvaluator(OpsProperties props, Supplier<OffsetDateTime> clock) {
        this.props = props;
        this.clock = clock;
    }

    /** ONLINE(≤ online-within) / STALE(≤ offline-after) / OFFLINE(更久或从未上报) */
    public EdgeHealth evaluate(OffsetDateTime lastSeenAt) {
        if (lastSeenAt == null) {
            return EdgeHealth.OFFLINE;
        }
        Duration elapsed = Duration.between(lastSeenAt, clock.get());
        if (elapsed.isNegative() || elapsed.compareTo(props.getEdge().getOnlineWithin()) <= 0) {
            return EdgeHealth.ONLINE;
        }
        if (elapsed.compareTo(props.getEdge().getOfflineAfter()) <= 0) {
            return EdgeHealth.STALE;
        }
        return EdgeHealth.OFFLINE;
    }
}
