package com.cnsportiot.cloud.harness.tool.port;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link TeacherAnalyticsPort} 的占位实现:确定性伪数据(按 studentId 派生),
 * 便于在算法侧口径落地前打通工具/闸/对话链路。所有返回 {@code sourceNote="placeholder"}。
 * 真实实现由算法侧替换本 bean。
 */
@Component
public class PlaceholderTeacherAnalyticsAdapter implements TeacherAnalyticsPort {

    private static final String NOTE = "placeholder";
    private static final List<String> CHECKPOINTS =
            List.of("stance", "hand_placement", "elbow_alignment", "release_timing", "follow_through");

    /** 由 studentId(+盐)派生的稳定 0..1 值。 */
    private static double unit(UUID id, String salt) {
        int h = (id.toString() + ":" + salt).hashCode();
        return (Math.abs(h) % 1000) / 1000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    public StudentSession studentSession(UUID studentId, UUID trainingSessionId) {
        List<CheckpointResult> cps = new ArrayList<>();
        double sum = 0;
        for (String cp : CHECKPOINTS) {
            double pr = round2(0.4 + 0.55 * unit(studentId, cp));
            sum += pr;
            cps.add(new CheckpointResult(cp, pr, 8 + (int) (unit(studentId, cp + "n") * 12)));
        }
        double overall = round2(sum / CHECKPOINTS.size());
        List<String> issues = cps.stream()
                .filter(c -> c.passRate() < 0.6)
                .map(c -> c.checkpoint() + " 通过率偏低")
                .toList();
        return new StudentSession(studentId, trainingSessionId, OffsetDateTime.now().toString(),
                overall, 3 + (int) (unit(studentId, "clips") * 5), cps, issues, NOTE);
    }

    @Override
    public MetricTrend studentTrend(UUID studentId, String metric, int weeks) {
        int n = Math.max(2, Math.min(weeks, 12));
        List<TrendPoint> pts = new ArrayList<>();
        double base = 0.4 + 0.4 * unit(studentId, metric);
        double slope = (unit(studentId, metric + ":slope") - 0.5) * 0.06;   // 每周 ±3%
        for (int i = 0; i < n; i++) {
            pts.add(new TrendPoint("W-" + (n - i), round2(Math.max(0, Math.min(1, base + slope * i)))));
        }
        String dir = slope > 0.005 ? "improving" : slope < -0.005 ? "worsening" : "flat";
        return new MetricTrend(studentId, MetricVocab.normalize(metric), pts, dir, NOTE);
    }

    @Override
    public GroupSummary groupSummary(List<UUID> studentIds, String metric, int weeks) {
        String m = MetricVocab.normalize(metric);
        List<StudentValue> vals = new ArrayList<>();
        for (UUID s : studentIds) {
            vals.add(new StudentValue(s, round2(0.4 + 0.55 * unit(s, m))));
        }
        List<Double> sorted = vals.stream().map(StudentValue::value).sorted().toList();
        double mean = sorted.isEmpty() ? 0 : round2(sorted.stream().mapToDouble(d -> d).average().orElse(0));
        double median = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        double min = sorted.isEmpty() ? 0 : sorted.get(0);
        double max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
        List<StudentValue> bottom = vals.stream()
                .sorted((a, b) -> Double.compare(a.value(), b.value()))
                .limit(Math.min(3, vals.size()))
                .toList();
        return new GroupSummary(studentIds.size(), m, mean, median, min, max, vals, bottom, "flat", NOTE);
    }

    @Override
    public List<CommonIssue> commonIssues(List<UUID> studentIds, int weeks) {
        List<CommonIssue> out = new ArrayList<>();
        int total = Math.max(1, studentIds.size());
        for (String cp : CHECKPOINTS) {
            int affected = (int) studentIds.stream().filter(s -> unit(s, cp) < 0.55).count();
            if (affected == 0) {
                continue;
            }
            double freq = round2((double) affected / total);
            String trend = unit(studentIds.get(0), cp + ":t") > 0.5 ? "improving" : "worsening";
            out.add(new CommonIssue("checkpoint:" + cp + " 未达", affected, freq, trend, NOTE));
        }
        out.sort((a, b) -> Integer.compare(b.affectedStudents(), a.affectedStudents()));
        return out;
    }
}

