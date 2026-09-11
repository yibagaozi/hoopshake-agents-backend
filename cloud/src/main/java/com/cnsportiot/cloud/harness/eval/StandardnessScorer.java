package com.cnsportiot.cloud.harness.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作标准度打分(纯函数,框架中立)。与 edge 侧实时评测同口径
 * 逐关节:{@code dev=|live-ref.mean|};{@code inband=dev≤tol};{@code s=clamp(1-dev/SCALE,0,1)};
 * 标准度 {@code =round(100·Σw·s/Σw)}(肘/膝等权,可调);{@code worst=argmax dev(且超带)}
 * 硬约束:死腕(right_wrist)不计;同源可比由调用方前置(不同源不应调用本类)
 */
public final class StandardnessScorer {

    private StandardnessScorer() {}

    /** 基准关节:均值 + 容差带 */
    public record JointRef(double mean, double tol) {}

    /** 单关节偏差明细 */
    public record JointDeviation(
            String joint, double live, double ref, double dev, double tol, boolean inband, double score) {}

    /**
     * @param standardness 0..100;无可比关节时为 null
     * @param comparable   是否有至少一个可比关节
     * @param inbandAll    是否所有可比关节都在容差带内
     * @param worstJoint   最需纠正的关节(偏差最大且超带);全达标时为 null
     */
    public record Result(
            Integer standardness, List<JointDeviation> perJoint, String worstJoint,
            boolean comparable, boolean inbandAll) {}

    /**
     * @param live       现测/均值角度(关节→度)
     * @param baseline   基准(关节→{mean,tol})
     * @param scaleDeg   归一尺度(默认 40°)
     * @param deadJoints 排除的死值关节(如 right_wrist)
     * @param weights    关节权重(null → 全 1)
     */
    public static Result score(Map<String, Double> live, Map<String, JointRef> baseline,
                               double scaleDeg, Set<String> deadJoints, Map<String, Double> weights) {
        double scale = scaleDeg <= 0 ? 40.0 : scaleDeg;
        List<JointDeviation> devs = new ArrayList<>();
        double wSum = 0, wScore = 0, worstDev = -1;
        String worst = null;
        boolean inbandAll = true;

        if (live != null && baseline != null) {
            for (Map.Entry<String, JointRef> e : baseline.entrySet()) {
                String joint = e.getKey();
                if (deadJoints != null && deadJoints.contains(joint)) {
                    continue;   // 死腕不计
                }
                Double lv = live.get(joint);
                if (lv == null) {
                    continue;   // live 缺该关节 → 跳过
                }
                double ref = e.getValue().mean();
                double tol = Math.max(0, e.getValue().tol());
                double dev = Math.abs(lv - ref);
                boolean inband = dev <= tol;
                double s = clamp(1 - dev / scale, 0, 1);
                double w = (weights != null && weights.get(joint) != null) ? weights.get(joint) : 1.0;
                devs.add(new JointDeviation(joint, r1(lv), r1(ref), r1(dev), r1(tol), inband, r2(s)));
                wSum += w;
                wScore += w * s;
                if (!inband) {
                    inbandAll = false;
                    if (dev > worstDev) {
                        worstDev = dev;
                        worst = joint;
                    }
                }
            }
        }
        if (devs.isEmpty() || wSum <= 0) {
            return new Result(null, devs, null, false, false);
        }
        int standardness = (int) Math.round(100 * wScore / wSum);
        return new Result(standardness, devs, worst, true, inbandAll);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

