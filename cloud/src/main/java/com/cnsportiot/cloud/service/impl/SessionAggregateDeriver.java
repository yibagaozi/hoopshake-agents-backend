package com.cnsportiot.cloud.service.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 会话聚合派生
 * 把同一 (session, student, action_type) 的多条 {@code action_clip} 聚合成 {@code session_aggregate.stats},
 * 键与读侧 {@code DbTeacherAnalyticsAdapter} 一致:
 * <ul>
 *   <li>{@code mean_release_angles}:各关节出手角均值(joint→deg),关节名口径;</li>
 *   <li>{@code angles_source}:各片段来源一致时取之,不一致标 {@code mixed};</li>
 *   <li>{@code release_sample_count}:贡献了角度的片段数;</li>
 *   <li>{@code fg_pct}:命中率(仅统计有 shotMade 结果的片段);</li>
 *   <li>{@code attempts}/{@code makes}/{@code clip_count}:计数,便于排查。</li>
 * </ul>
 *
 * <p>角度来源约定:每条 clip 的 {@code score} jsonb 里带 {@code release_angles}(joint→deg,由算法侧在出手相位算好)
 * 与 {@code angles_source}。缺则该片段不贡献角度(仍计入 attempts / fg_pct)
 */
public final class SessionAggregateDeriver {

    private SessionAggregateDeriver() {}

    /** 单条片段的聚合输入(从 action_clip 抽取) */
    public record ClipInput(Boolean shotMade, Map<String, Double> releaseAngles, String anglesSource) {}

    public static Map<String, Object> derive(List<ClipInput> clips) {
        int attempts = clips.size();
        int makes = 0;
        int outcomes = 0;                                   // 有 make/miss 结论的片段
        Map<String, Double> sum = new LinkedHashMap<>();
        Map<String, Integer> cnt = new LinkedHashMap<>();
        int angleClips = 0;
        Set<String> sources = new LinkedHashSet<>();

        for (ClipInput c : clips) {
            if (c.shotMade() != null) {
                outcomes++;
                if (c.shotMade()) {
                    makes++;
                }
            }
            boolean contributed = false;
            if (c.releaseAngles() != null) {
                for (Map.Entry<String, Double> e : c.releaseAngles().entrySet()) {
                    Double v = e.getValue();
                    if (v != null && Double.isFinite(v)) {
                        sum.merge(e.getKey(), v, Double::sum);
                        cnt.merge(e.getKey(), 1, Integer::sum);
                        contributed = true;
                    }
                }
            }
            if (contributed) {
                angleClips++;
            }
            if (c.anglesSource() != null && !c.anglesSource().isBlank()) {
                sources.add(c.anglesSource());
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("clip_count", attempts);
        stats.put("attempts", attempts);
        stats.put("makes", makes);
        if (outcomes > 0) {
            stats.put("fg_pct", round4((double) makes / outcomes));
        }
        if (!cnt.isEmpty()) {
            Map<String, Double> mean = new LinkedHashMap<>();
            cnt.forEach((joint, n) -> mean.put(joint, round1(sum.get(joint) / n)));
            stats.put("mean_release_angles", mean);
            stats.put("release_sample_count", angleClips);
            String src = sources.size() == 1 ? sources.iterator().next()
                    : sources.isEmpty() ? null : "mixed";
            if (src != null) {
                stats.put("angles_source", src);
            }
        }
        return stats;
    }

    /** 从 clip.score(jsonb)提取 release 角(joint→deg)。约定键 {@code release_angles} */
    @SuppressWarnings("unchecked")
    public static Map<String, Double> extractReleaseAngles(Map<String, Object> score) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (score == null) {
            return out;
        }
        Object ra = score.get("release_angles");
        if (ra instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                Double v = toDouble(e.getValue());
                if (v != null) {
                    out.put(e.getKey(), v);
                }
            }
        }
        return out;
    }

    /** 从 clip.score 提取 {@code angles_source} */
    public static String extractAnglesSource(Map<String, Object> score) {
        Object v = score == null ? null : score.get("angles_source");
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (o != null) {
            try {
                double d = Double.parseDouble(String.valueOf(o));
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}

