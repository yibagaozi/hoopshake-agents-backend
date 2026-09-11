package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.service.impl.SessionAggregateDeriver.ClipInput;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** L2 会话聚合派生:命中率、逐关节均值、来源一致性、缺量/缺结果的降级、score 抽取。纯单测。 */
class SessionAggregateDeriverTest {

    private static Map<String, Double> angles(String j, double v) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(j, v);
        return m;
    }

    @Test void fgPct_onlyCountsClipsWithOutcome() {
        var stats = SessionAggregateDeriver.derive(List.of(
                new ClipInput(true, Map.of(), "triangulated_3d"),
                new ClipInput(false, Map.of(), "triangulated_3d"),
                new ClipInput(true, Map.of(), "triangulated_3d"),
                new ClipInput(null, Map.of(), "triangulated_3d")));   // 无结论不计入分母
        assertThat(stats.get("attempts")).isEqualTo(4);
        assertThat(stats.get("makes")).isEqualTo(2);
        assertThat((double) stats.get("fg_pct")).isEqualTo(0.6667);   // 2/3
    }

    @Test void meanAngles_perJoint_ignoreNonFinite() {
        Map<String, Double> a0 = new LinkedHashMap<>();
        a0.put("right_elbow", 150.0);
        a0.put("right_knee", 100.0);
        Map<String, Double> a1 = new LinkedHashMap<>();
        a1.put("right_elbow", 170.0);
        a1.put("right_knee", Double.NaN);   // 该关节本条不计
        var stats = SessionAggregateDeriver.derive(List.of(
                new ClipInput(true, a0, "triangulated_3d"),
                new ClipInput(false, a1, "triangulated_3d")));
        @SuppressWarnings("unchecked")
        Map<String, Object> mra = (Map<String, Object>) stats.get("mean_release_angles");
        assertThat(((Number) mra.get("right_elbow")).doubleValue()).isEqualTo(160.0);   // (150+170)/2
        assertThat(((Number) mra.get("right_knee")).doubleValue()).isEqualTo(100.0);    // 仅 1 条有效
        assertThat(stats.get("release_sample_count")).isEqualTo(2);
        assertThat(stats.get("angles_source")).isEqualTo("triangulated_3d");
    }

    @Test void anglesSource_mixedWhenClipsDisagree() {
        var stats = SessionAggregateDeriver.derive(List.of(
                new ClipInput(true, angles("right_elbow", 160.0), "triangulated_3d"),
                new ClipInput(true, angles("right_elbow", 160.0), "pseudo3d_fallback")));
        assertThat(stats.get("angles_source")).isEqualTo("mixed");   // 不同源 → mixed(下游同源守卫据此判不可比)
    }

    @Test void noOutcome_fgPctAbsent() {
        var stats = SessionAggregateDeriver.derive(List.of(
                new ClipInput(null, angles("right_elbow", 160.0), "triangulated_3d")));
        assertThat(stats).doesNotContainKey("fg_pct");
    }

    @Test void noAngles_meanAndSourceAbsent() {
        var stats = SessionAggregateDeriver.derive(List.of(
                new ClipInput(true, Map.of(), null),
                new ClipInput(false, null, null)));
        assertThat(stats).doesNotContainKey("mean_release_angles");
        assertThat(stats).doesNotContainKey("angles_source");
        assertThat(stats).doesNotContainKey("release_sample_count");
        assertThat((double) stats.get("fg_pct")).isEqualTo(0.5);   // 计数照常
    }

    @Test void extractReleaseAngles_readsScoreConvention() {
        Map<String, Object> score = Map.of(
                "release_angles", Map.of("right_elbow", 165, "right_wrist", "180", "bad", "x"),
                "angles_source", "triangulated_3d");
        Map<String, Double> ra = SessionAggregateDeriver.extractReleaseAngles(score);
        assertThat(ra).containsEntry("right_elbow", 165.0).containsEntry("right_wrist", 180.0);
        assertThat(ra).doesNotContainKey("bad");   // 非数值跳过
        assertThat(SessionAggregateDeriver.extractAnglesSource(score)).isEqualTo("triangulated_3d");
    }

    @Test void extract_nullScore_safe() {
        assertThat(SessionAggregateDeriver.extractReleaseAngles(null)).isEmpty();
        assertThat(SessionAggregateDeriver.extractAnglesSource(null)).isNull();
    }
}
