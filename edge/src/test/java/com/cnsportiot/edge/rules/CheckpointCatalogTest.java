package com.cnsportiot.edge.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 词表加载:剔除非法条目、按(动作,相位)匹配、通配。 */
class CheckpointCatalogTest {

    @Test void dropsInvalid_keepsValid() {
        CheckpointProperties props = new CheckpointProperties();
        props.setCheckpoints(List.of(
                valid(),
                disabled(),
                minGreaterThanMax(),
                blankMetric(),
                noBand()));
        CheckpointCatalog catalog = new CheckpointCatalog(props);
        assertThat(catalog.size()).isEqualTo(1);   // 仅 valid 存活
    }

    @Test void match_exactAndWildcard() {
        CheckpointProperties props = new CheckpointProperties();
        props.setCheckpoints(List.of(
                rule("ft.elbow", "free_throw", "release", "elbow_angle", 160.0, 180.0, false),
                rule("wild", "*", "*", "trunk_lean_deg", null, 25.0, false)));
        CheckpointCatalog catalog = new CheckpointCatalog(props);

        assertThat(catalog.match("free_throw", "release")).extracting(CheckpointRule::getId)
                .containsExactlyInAnyOrder("ft.elbow", "wild");
        assertThat(catalog.match("pass", "load")).extracting(CheckpointRule::getId)
                .containsExactly("wild");   // 只命中通配
        assertThat(catalog.match(null, "release")).isEmpty();
    }

    // ---- fixtures ----

    private static CheckpointRule valid() {
        return rule("ok", "free_throw", "release", "elbow_angle", 160.0, 180.0, false);
    }

    private static CheckpointRule disabled() {
        CheckpointRule r = valid();
        r.setId("disabled");
        r.setEnabled(false);
        return r;
    }

    private static CheckpointRule minGreaterThanMax() {
        return rule("badband", "free_throw", "load", "knee_angle", 140.0, 110.0, false);
    }

    private static CheckpointRule blankMetric() {
        return rule("nometric", "free_throw", "load", "  ", 110.0, 140.0, false);
    }

    private static CheckpointRule noBand() {
        return rule("noband", "free_throw", "load", "knee_angle", null, null, false);
    }

    private static CheckpointRule rule(String id, String action, String phase, String metric,
                                       Double min, Double max, boolean safety) {
        CheckpointRule r = new CheckpointRule();
        r.setId(id);
        r.setActionType(action);
        r.setPhase(phase);
        r.setMetric(metric);
        r.setMin(min);
        r.setMax(max);
        r.setSafety(safety);
        return r;
    }
}
