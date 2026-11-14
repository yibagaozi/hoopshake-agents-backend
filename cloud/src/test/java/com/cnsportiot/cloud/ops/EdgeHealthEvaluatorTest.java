package com.cnsportiot.cloud.ops;

import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeHealth;
import com.cnsportiot.cloud.ops.evaluator.EdgeHealthEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 由 last_seen_at 派生健康态:ONLINE ≤90s,STALE ≤10min,否则 OFFLINE。 */
class EdgeHealthEvaluatorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 5, 10, 0, 0, 0, ZoneOffset.UTC);

    private EdgeHealthEvaluator evaluator() {
        OpsProperties p = new OpsProperties();
        p.getEdge().setOnlineWithin(Duration.ofSeconds(90));
        p.getEdge().setOfflineAfter(Duration.ofMinutes(10));
        return new EdgeHealthEvaluator(p, () -> NOW);
    }

    @Test void onlineWithinThreshold() {
        assertThat(evaluator().evaluate(NOW.minusSeconds(30))).isEqualTo(EdgeHealth.ONLINE);
        assertThat(evaluator().evaluate(NOW.minusSeconds(90))).isEqualTo(EdgeHealth.ONLINE);   // 边界含
    }

    @Test void staleBetweenThresholds() {
        assertThat(evaluator().evaluate(NOW.minusSeconds(91))).isEqualTo(EdgeHealth.STALE);
        assertThat(evaluator().evaluate(NOW.minusMinutes(10))).isEqualTo(EdgeHealth.STALE);     // 边界含
    }

    @Test void offlineBeyondThreshold() {
        assertThat(evaluator().evaluate(NOW.minusMinutes(11))).isEqualTo(EdgeHealth.OFFLINE);
        assertThat(evaluator().evaluate(NOW.minusHours(3))).isEqualTo(EdgeHealth.OFFLINE);
    }

    @Test void nullOrFutureLastSeen() {
        assertThat(evaluator().evaluate(null)).isEqualTo(EdgeHealth.OFFLINE);
        assertThat(evaluator().evaluate(NOW.plusSeconds(5))).isEqualTo(EdgeHealth.ONLINE);       // 时钟漂移容忍
    }
}
