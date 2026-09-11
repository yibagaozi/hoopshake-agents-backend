package com.cnsportiot.edge.rules;

import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.edge.realtime.WsEvents.ActionSample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 实时 2D 规则引擎:达标带判定、话术选取、正反馈、安全项、置信闸、缺量跳过、去抖、通配。纯单测,不需 Spring。 */
class RealtimeRuleEngineTest {

    private static final UUID STU = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private TestClock clock;
    private CheckpointProperties props;
    private RealtimeRuleEngine engine;

    @BeforeEach
    void setup() {
        clock = new TestClock(Instant.parse("2026-09-10T00:00:00Z"));
        props = new CheckpointProperties();
        props.setMinConfidence(0.35);
        props.setDefaultCooldownMs(1_000);
        props.setCheckpoints(List.of(
                band("ft.release.elbow", "free_throw", "release", "elbow_angle", 160.0, 180.0,
                        "MAJOR", false, "肘没伸直", "肘过伸", "伸展到位"),
                safety("safety.trunk_lean", "*", "*", "trunk_lean_deg", null, 25.0, "后仰过大")));
        engine = new RealtimeRuleEngine(new CheckpointCatalog(props), props, clock);
    }

    @Test void belowMin_emitsLowCue_major() {
        List<RuleHit> hits = engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 140.0), 0.9));
        assertThat(hits).hasSize(1);
        RuleHit h = hits.get(0);
        assertThat(h.checkpointId()).isEqualTo("ft.release.elbow");
        assertThat(h.severity()).isEqualTo(FeedbackSeverity.MAJOR);
        assertThat(h.cueText()).isEqualTo("肘没伸直");
        assertThat(h.safety()).isFalse();
        assertThat(h.eventId()).contains("ft.release.elbow");
    }

    @Test void aboveMax_emitsHighCue() {
        List<RuleHit> hits = engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 190.0), 0.9));
        assertThat(hits).singleElement().satisfies(h -> assertThat(h.cueText()).isEqualTo("肘过伸"));
    }

    @Test void inBand_withCueOk_emitsPositive() {
        List<RuleHit> hits = engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 170.0), 0.9));
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.severity()).isEqualTo(FeedbackSeverity.POSITIVE);
            assertThat(h.cueText()).isEqualTo("伸展到位");
        });
    }

    @Test void inBand_withoutCueOk_noHit() {
        // 去掉正反馈的同一规则
        props.setCheckpoints(List.of(
                band("x", "free_throw", "release", "elbow_angle", 160.0, 180.0, "MINOR", false, "低", "高", null)));
        engine = new RealtimeRuleEngine(new CheckpointCatalog(props), props, clock);
        assertThat(engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 170.0), 0.9))).isEmpty();
    }

    @Test void safetyOutOfBand_flaggedSafety() {
        List<RuleHit> hits = engine.evaluate(
                sample("free_throw", "release", Map.of("elbow_angle", 170.0, "trunk_lean_deg", 40.0), 0.9));
        // elbow 达标(无 cueOk? 有 → POSITIVE)+ 后仰超界(safety)
        assertThat(hits).anySatisfy(h -> {
            assertThat(h.checkpointId()).isEqualTo("safety.trunk_lean");
            assertThat(h.safety()).isTrue();
        });
    }

    @Test void lowConfidence_skipped() {
        assertThat(engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 140.0), 0.2))).isEmpty();
    }

    @Test void missingMetric_skipped() {
        assertThat(engine.evaluate(sample("free_throw", "release", Map.of("knee_angle", 100.0), 0.9))).isEmpty();
    }

    @Test void debounce_suppressesWithinCooldown_thenEmitsAfter() {
        assertThat(engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 140.0), 0.9))).hasSize(1);
        assertThat(engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 140.0), 0.9))).isEmpty();
        clock.advanceMs(1_500);   // 超过 1000ms 冷却
        assertThat(engine.evaluate(sample("free_throw", "release", Map.of("elbow_angle", 140.0), 0.9))).hasSize(1);
    }

    @Test void wildcard_appliesToAnyActionAndPhase() {
        List<RuleHit> hits = engine.evaluate(sample("layup", "gather", Map.of("trunk_lean_deg", 40.0), 0.9));
        assertThat(hits).singleElement().satisfies(h -> assertThat(h.checkpointId()).isEqualTo("safety.trunk_lean"));
    }

    // ---- helpers ----

    private ActionSample sample(String action, String phase, Map<String, Object> measured, double conf) {
        return new ActionSample(null, STU, "小明", "2024001", action, phase, measured,
                "high", conf, "cam_03", 5300.0, null);
    }

    private static CheckpointRule band(String id, String action, String phase, String metric,
                                       Double min, Double max, String severity, boolean safety,
                                       String low, String high, String ok) {
        CheckpointRule r = new CheckpointRule();
        r.setId(id);
        r.setActionType(action);
        r.setPhase(phase);
        r.setMetric(metric);
        r.setMin(min);
        r.setMax(max);
        r.setSeverity(severity);
        r.setSafety(safety);
        r.setCueLow(low);
        r.setCueHigh(high);
        r.setCueOk(ok);
        return r;
    }

    private static CheckpointRule safety(String id, String action, String phase, String metric,
                                         Double min, Double max, String high) {
        CheckpointRule r = band(id, action, phase, metric, min, max, "MAJOR", true, null, high, null);
        return r;
    }

    /** 可推进的测试时钟。 */
    private static final class TestClock extends Clock {
        private Instant instant;
        private final ZoneId zone;
        TestClock(Instant i) { this(i, ZoneOffset.UTC); }
        TestClock(Instant i, ZoneId z) { this.instant = i; this.zone = z; }
        void advanceMs(long ms) { instant = instant.plusMillis(ms); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return new TestClock(instant, z); }
        @Override public Instant instant() { return instant; }
    }
}
