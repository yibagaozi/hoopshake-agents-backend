package com.cnsportiot.cloud.harness.tool.port;

import com.cnsportiot.cloud.domain.entity.ActionClip;
import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import com.cnsportiot.cloud.repository.ActionClipRepository;
import com.cnsportiot.cloud.repository.InstantFeedbackRepository;
import com.cnsportiot.cloud.repository.SessionAggregateRepository;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link TeacherAnalyticsPort} 的**读库实现**:从 action_clip / session_aggregate / instant_feedback 取真实数据
 * 入参 studentIds 均已由 TeacherScopeGuardHook 校验归属,此处不再判权
 */
@Component
@Primary
public class DbTeacherAnalyticsAdapter implements TeacherAnalyticsPort {

    private static final String NOTE = "db";

    private final ActionClipRepository clipRepo;
    private final SessionAggregateRepository aggRepo;
    private final InstantFeedbackRepository feedbackRepo;

    public DbTeacherAnalyticsAdapter(ActionClipRepository clipRepo, SessionAggregateRepository aggRepo,
                                     InstantFeedbackRepository feedbackRepo) {
        this.clipRepo = clipRepo;
        this.aggRepo = aggRepo;
        this.feedbackRepo = feedbackRepo;
    }

    @Override
    public StudentSession studentSession(UUID studentId, UUID trainingSessionId) {
        UUID sid = trainingSessionId;
        if (sid == null) {
            List<UUID> recent = clipRepo.findRecentSessionIdsByStudent(studentId, PageRequest.of(0, 1));
            sid = recent.isEmpty() ? null : recent.get(0);
        }
        if (sid == null) {
            return new StudentSession(studentId, null, null, 0.0, 0, List.of(), List.of("暂无训练数据"), NOTE);
        }
        List<ActionClip> clips = clipRepo.findBySessionIdAndStudentIdOrderByClipIndex(sid, studentId);
        int attempts = clips.size();
        int makes = (int) clips.stream().filter(c -> Boolean.TRUE.equals(c.getShotMade())).count();
        double madeRate = attempts == 0 ? 0.0 : round2((double) makes / attempts);

        List<CheckpointResult> cps = new ArrayList<>();
        for (InstantFeedbackRepository.CheckpointAgg a : feedbackRepo.checkpointAgg(studentId, sid)) {
            double pass = a.getTotal() == 0 ? 0.0 : round2(1.0 - (double) a.getMajor() / a.getTotal());
            cps.add(new CheckpointResult(a.getCheckpointId(), pass, (int) a.getTotal()));
        }
        List<String> topIssues = feedbackRepo.findByStudentIdAndSessionIdOrderByOccurredAtAsc(studentId, sid).stream()
                .filter(f -> f.getSeverity() == FeedbackSeverity.MAJOR)
                .map(InstantFeedback::getCueText).filter(t -> t != null && !t.isBlank())
                .distinct().limit(3).toList();

        return new StudentSession(studentId, sid, null, madeRate, attempts, cps, topIssues, NOTE);
    }

    @Override
    public MetricTrend studentTrend(UUID studentId, String metric, int weeks) {
        String m = MetricVocab.normalize(metric);
        String key = mapMetric(m);
        List<TrendPoint> pts = new ArrayList<>();
        for (Object[] row : aggRepo.findWithTimeByStudent(studentId)) {
            SessionAggregate agg = (SessionAggregate) row[0];
            OffsetDateTime at = (OffsetDateTime) row[1];
            Double v = metricValue(agg.getStats(), key);
            if (v != null) {
                pts.add(new TrendPoint(at == null ? "-" : at.toLocalDate().toString(), round2(v)));
            }
        }
        if (weeks > 0 && pts.size() > weeks) {
            pts = pts.subList(pts.size() - weeks, pts.size());
        }
        return new MetricTrend(studentId, m, pts, direction(pts), NOTE);
    }

    @Override
    public GroupSummary groupSummary(List<UUID> studentIds, String metric, int weeks) {
        String m = MetricVocab.normalize(metric);
        String key = mapMetric(m);
        // 每个学生取"最近一次"聚合的指标值
        Map<UUID, Double> latest = new LinkedHashMap<>();
        Map<UUID, OffsetDateTime> latestAt = new LinkedHashMap<>();
        if (!studentIds.isEmpty()) {
            for (Object[] row : aggRepo.findWithTimeByStudentIn(studentIds)) {   // 时间升序
                SessionAggregate agg = (SessionAggregate) row[0];
                OffsetDateTime at = (OffsetDateTime) row[1];
                Double v = metricValue(agg.getStats(), key);
                if (v == null) {
                    continue;
                }
                latest.put(agg.getStudentId(), round2(v));   // 升序遍历 → 后写即最近
                latestAt.put(agg.getStudentId(), at);
            }
        }
        List<StudentValue> perStudent = new ArrayList<>();
        latest.forEach((sid, v) -> perStudent.add(new StudentValue(sid, v)));
        List<Double> vals = perStudent.stream().map(StudentValue::value).sorted().toList();
        double mean = vals.isEmpty() ? 0 : round2(vals.stream().mapToDouble(d -> d).average().orElse(0));
        double median = vals.isEmpty() ? 0 : vals.get(vals.size() / 2);
        double min = vals.isEmpty() ? 0 : vals.get(0);
        double max = vals.isEmpty() ? 0 : vals.get(vals.size() - 1);
        List<StudentValue> bottom = perStudent.stream()
                .sorted(Comparator.comparingDouble(StudentValue::value))
                .limit(Math.min(3, perStudent.size())).toList();
        return new GroupSummary(perStudent.size(), m, mean, median, min, max, perStudent, bottom, "flat", NOTE);
    }

    @Override
    public List<CommonIssue> commonIssues(List<UUID> studentIds, int weeks) {
        if (studentIds.isEmpty()) {
            return List.of();
        }
        int total = studentIds.size();
        List<CommonIssue> out = new ArrayList<>();
        for (InstantFeedbackRepository.CommonIssueRow r : feedbackRepo.findCommonIssues(studentIds, FeedbackSeverity.MAJOR)) {
            double freq = round2((double) r.getAffected() / total);
            out.add(new CommonIssue(r.getCheckpointId() + " 未达", (int) r.getAffected(), freq, "flat", NOTE));
        }
        return out;
    }

    @Override
    public ActionAngleProfile actionAngleProfile(UUID studentId, String actionType) {
        // 取该学生该动作"最近一次"有出手角度均值的聚合(findWithTimeByStudent 已按时间升序 → 后写即最近)
        SessionAggregate latest = null;
        for (Object[] row : aggRepo.findWithTimeByStudent(studentId)) {
            SessionAggregate agg = (SessionAggregate) row[0];
            if (actionType != null && !actionType.equalsIgnoreCase(agg.getActionType())) {
                continue;
            }
            if (!readAngles(agg.getStats()).isEmpty()) {
                latest = agg;   // 升序遍历,最后一个匹配即最近
            }
        }
        if (latest == null) {
            return new ActionAngleProfile(studentId, actionType, Map.of(), null, 0, NOTE + ":no-data");
        }
        Map<String, Double> angles = readAngles(latest.getStats());
        String src = readString(latest.getStats(), "angles_source");
        int n = (int) Math.round(orZero(toDouble(latest.getStats() == null ? null
                : latest.getStats().get("release_sample_count"))));
        return new ActionAngleProfile(studentId, actionType, angles, src, n, NOTE);
    }

    // helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Double> readAngles(Map<String, Object> stats) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (stats == null) {
            return out;
        }
        Object mra = stats.get("mean_release_angles");
        if (mra instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                Double v = toDouble(e.getValue());
                if (v != null) {
                    out.put(e.getKey(), v);
                }
            }
        }
        return out;
    }

    private static String readString(Map<String, Object> stats, String key) {
        Object v = stats == null ? null : stats.get(key);
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    private static double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String mapMetric(String m) {
        // 教师词表 → 库内 stats 键。真实数据里有 fg_pct 与 mean_release_angles.<joint>
        if (m.startsWith("checkpoint")) {
            return "fg_pct";   // 暂以命中率代表"检查点通过";真实检查点通过率待评测引擎
        }
        if (m.startsWith("action.")) {
            String[] p = m.split("\\.");
            return p.length >= 3 ? "release." + p[2] : "fg_pct";
        }
        return "fg_pct";
    }

    @SuppressWarnings("unchecked")
    private static Double metricValue(Map<String, Object> stats, String key) {
        if (stats == null) {
            return null;
        }
        if (key.startsWith("release.")) {
            Object mra = stats.get("mean_release_angles");
            if (mra instanceof Map<?, ?> map) {
                return toDouble(((Map<String, Object>) map).get(key.substring("release.".length())));
            }
            return null;
        }
        return toDouble(stats.get(key));
    }

    private static String direction(List<TrendPoint> pts) {
        if (pts.size() < 2) {
            return "flat";
        }
        double d = pts.get(pts.size() - 1).value() - pts.get(0).value();
        return d > 1e-6 ? "improving" : d < -1e-6 ? "worsening" : "flat";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o));
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}

