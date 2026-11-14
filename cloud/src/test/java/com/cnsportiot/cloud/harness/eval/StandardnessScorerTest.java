package com.cnsportiot.cloud.harness.eval;

import com.cnsportiot.cloud.harness.eval.StandardnessScorer.JointRef;
import com.cnsportiot.cloud.harness.eval.StandardnessScorer.Result;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 动作标准度打分:完全达标=100、超差扣分、死腕排除、worst 选取、容差带、不可比。 */
class StandardnessScorerTest {

    private static final Set<String> DEAD = Set.of("right_wrist");

    private Map<String, JointRef> baseline() {
        Map<String, JointRef> b = new LinkedHashMap<>();
        b.put("right_elbow", new JointRef(170, 15));
        b.put("left_elbow", new JointRef(165, 15));
        b.put("right_knee", new JointRef(160, 15));
        b.put("left_knee", new JointRef(160, 15));
        return b;
    }

    @Test void perfectMatch_is100_allInband() {
        Map<String, Double> live = Map.of(
                "right_elbow", 170.0, "left_elbow", 165.0, "right_knee", 160.0, "left_knee", 160.0);
        Result r = StandardnessScorer.score(live, baseline(), 40, DEAD, null);
        assertThat(r.standardness()).isEqualTo(100);
        assertThat(r.inbandAll()).isTrue();
        assertThat(r.worstJoint()).isNull();
        assertThat(r.comparable()).isTrue();
    }

    @Test void deviationsLowerScore_andPickWorst() {
        // right_elbow 差 40°(超带,且最大)、right_knee 差 20°(超带)、其余达标
        Map<String, Double> live = Map.of(
                "right_elbow", 130.0, "left_elbow", 165.0, "right_knee", 140.0, "left_knee", 160.0);
        Result r = StandardnessScorer.score(live, baseline(), 40, DEAD, null);
        assertThat(r.standardness()).isLessThan(100).isGreaterThan(0);
        assertThat(r.inbandAll()).isFalse();
        assertThat(r.worstJoint()).isEqualTo("right_elbow");   // 偏差最大且超带
    }

    @Test void withinTolerance_stillCountsButInband() {
        // 每个关节差 10°(≤tol 15 → inband),但 s=1-10/40=0.75 → 标准度=75
        Map<String, Double> live = Map.of(
                "right_elbow", 180.0, "left_elbow", 175.0, "right_knee", 170.0, "left_knee", 170.0);
        Result r = StandardnessScorer.score(live, baseline(), 40, DEAD, null);
        assertThat(r.inbandAll()).isTrue();          // 都在容差带内
        assertThat(r.standardness()).isEqualTo(75);  // 但标准度反映偏差幅度
        assertThat(r.worstJoint()).isNull();
    }

    @Test void deadJoint_excluded() {
        Map<String, JointRef> b = baseline();
        b.put("right_wrist", new JointRef(180, 15));      // 死腕加进基准
        Map<String, Double> live = new LinkedHashMap<>(Map.of(
                "right_elbow", 170.0, "left_elbow", 165.0, "right_knee", 160.0, "left_knee", 160.0));
        live.put("right_wrist", 90.0);                    // 死腕给个离谱值
        Result r = StandardnessScorer.score(live, b, 40, DEAD, null);
        assertThat(r.standardness()).isEqualTo(100);     // 死腕不计 → 仍满分
        assertThat(r.perJoint()).noneMatch(d -> d.joint().equals("right_wrist"));
    }

    @Test void noComparableJoints_notComparable() {
        Map<String, Double> live = Map.of("nose", 10.0);   // 与基准无交集
        Result r = StandardnessScorer.score(live, baseline(), 40, DEAD, null);
        assertThat(r.comparable()).isFalse();
        assertThat(r.standardness()).isNull();
    }

    @Test void weights_biasTowardShootingSide() {
        // 右肘(投篮侧)权重高;右肘差大时,加权标准度应低于等权
        Map<String, Double> live = Map.of(
                "right_elbow", 130.0, "left_elbow", 165.0, "right_knee", 160.0, "left_knee", 160.0);
        int equal = StandardnessScorer.score(live, baseline(), 40, DEAD, null).standardness();
        Map<String, Double> w = Map.of("right_elbow", 3.0);   // 其余默认 1
        int weighted = StandardnessScorer.score(live, baseline(), 40, DEAD, w).standardness();
        assertThat(weighted).isLessThan(equal);
    }

    @Test void deviationBeyondScale_clampsToZero_notNegative() {
        Map<String, Double> live = Map.of(
                "right_elbow", 0.0, "left_elbow", 0.0, "right_knee", 0.0, "left_knee", 0.0);   // 差 >> scale
        Result r = StandardnessScorer.score(live, baseline(), 40, DEAD, null);
        assertThat(r.standardness()).isZero();   // clamp 到 0,不为负
    }
}
