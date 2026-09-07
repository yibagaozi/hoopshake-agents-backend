package com.cnsportiot.cloud.harness.tool.port;

import com.cnsportiot.cloud.domain.entity.ActionClip;
import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.cloud.repository.ActionClipRepository;
import com.cnsportiot.cloud.repository.InstantFeedbackRepository;
import com.cnsportiot.cloud.repository.SessionAggregateRepository;
import com.cnsportiot.cloud.repository.TrainingSessionRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link StudentDataPort} 的**读库实现**:从 action_clip / session_aggregate / instant_feedback /
 * training_session 取真实数据(即 edge 解析后经 /api/ingest 落库的形态)
 * 每条 DTO sourceNote="db"。仅返回入参 studentId 的数据
 */
@Component
@Primary
public class DbStudentDataAdapter implements StudentDataPort {

    private static final String NOTE = "db";
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ActionClipRepository clipRepo;
    private final SessionAggregateRepository aggRepo;
    private final InstantFeedbackRepository feedbackRepo;
    private final TrainingSessionRepository sessionRepo;

    public DbStudentDataAdapter(ActionClipRepository clipRepo, SessionAggregateRepository aggRepo,
                                InstantFeedbackRepository feedbackRepo, TrainingSessionRepository sessionRepo) {
        this.clipRepo = clipRepo;
        this.aggRepo = aggRepo;
        this.feedbackRepo = feedbackRepo;
        this.sessionRepo = sessionRepo;
    }

    @Override
    public boolean owns(UUID studentId, ResourceType type, String resourceId) {
        if (studentId == null || resourceId == null || resourceId.isBlank()) {
            return false;
        }
        UUID rid;
        try {
            rid = UUID.fromString(resourceId.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return switch (type) {
            case SESSION -> clipRepo.existsBySessionIdAndStudentId(rid, studentId);
            case CLIP -> clipRepo.existsByIdAndStudentId(rid, studentId);
        };
    }

    @Override
    public List<ClipSummary> recentClips(UUID studentId, int limit) {
        int n = Math.min(Math.max(limit, 1), 20);
        List<ActionClip> clips = clipRepo.findRecentByStudent(studentId, PageRequest.of(0, n));
        Map<UUID, TrainingSession> sessions = new LinkedHashMap<>();
        List<ClipSummary> out = new ArrayList<>(clips.size());
        for (ActionClip c : clips) {
            TrainingSession s = sessions.computeIfAbsent(c.getSessionId(),
                    id -> sessionRepo.findById(id).orElse(null));
            boolean made = Boolean.TRUE.equals(c.getShotMade());
            out.add(new ClipSummary(
                    c.getSessionId(), c.getActionType(), recordedAt(s),
                    made ? 1 : 0, 1, made ? null : reasonOf(c), NOTE));
        }
        return out;
    }

    @Override
    public SessionSummary sessionSummary(UUID studentId, UUID trainingSessionId) {
        UUID sid = resolveSession(studentId, trainingSessionId);
        if (sid == null) {
            return new SessionSummary(null, null, null, 0, 0,
                    List.of("暂无训练数据"), "还没有可复盘的训练,先去练一组吧。", NOTE);
        }
        List<ActionClip> clips = clipRepo.findBySessionIdAndStudentIdOrderByClipIndex(sid, studentId);
        int total = clips.size();
        int made = (int) clips.stream().filter(c -> Boolean.TRUE.equals(c.getShotMade())).count();
        String action = clips.stream().map(ActionClip::getActionType).findFirst().orElse(null);
        TrainingSession s = sessionRepo.findById(sid).orElse(null);

        List<String> findings = new ArrayList<>();
        if (total > 0) {
            findings.add(String.format("本次 %d 投 %d 中,命中率 %.0f%%", total, made, 100.0 * made / total));
        }
        for (SessionAggregate agg : aggRepo.findBySessionIdAndStudentId(sid, studentId)) {
            Object fg = agg.getStats() == null ? null : agg.getStats().get("fg_pct");
            if (fg != null) {
                findings.add(agg.getActionType() + " 命中率 " + fg + "%");
            }
        }
        feedbackRepo.findByStudentIdAndSessionIdOrderByOccurredAtAsc(studentId, sid).stream()
                .map(InstantFeedback::getCueText).filter(t -> t != null && !t.isBlank())
                .limit(2).forEach(findings::add);

        return new SessionSummary(sid, action, recordedAt(s), made, total,
                findings, "和自己比、看趋势;抓住反馈里最常出现的那个点重点练。", NOTE);
    }

    @Override
    public List<FeedbackEntry> instantFeedbackLog(UUID studentId, UUID trainingSessionId) {
        UUID sid = resolveSession(studentId, trainingSessionId);
        if (sid == null) {
            return List.of();
        }
        List<FeedbackEntry> out = new ArrayList<>();
        for (InstantFeedback f : feedbackRepo.findByStudentIdAndSessionIdOrderByOccurredAtAsc(studentId, sid)) {
            String at = f.getOccurredAt() == null ? "" : f.getOccurredAt().format(HMS);
            out.add(new FeedbackEntry(at, f.getCheckpointId(), f.getCueText(),
                    f.getSeverity() == null ? null : f.getSeverity().name(), NOTE));
        }
        return out;
    }

    @Override
    public ProgressTrend progressTrend(UUID studentId, String metric, int weeks) {
        String m = (metric == null || metric.isBlank()) ? "fg_pct" : metric.trim();
        List<Object[]> rows = aggRepo.findWithTimeByStudent(studentId);
        List<ProgressTrend.TrendPoint> pts = new ArrayList<>();
        for (Object[] row : rows) {
            SessionAggregate agg = (SessionAggregate) row[0];
            OffsetDateTime at = (OffsetDateTime) row[1];
            Double v = metricValue(agg.getStats(), m);
            if (v != null) {
                pts.add(new ProgressTrend.TrendPoint(label(at), v));
            }
        }
        int w = weeks <= 0 ? pts.size() : Math.min(weeks, pts.size());
        if (pts.size() > w) {
            pts = pts.subList(pts.size() - w, pts.size());
        }
        String summary = pts.size() < 2 ? "样本不足,多练几次才能看出趋势。"
                : (pts.get(pts.size() - 1).value() >= pts.get(0).value() ? m + " 总体向好,保持。" : m + " 近期回落,注意稳定性。");
        return new ProgressTrend(m, "近 " + pts.size() + " 次", pts, summary, NOTE);
    }

    @Override
    public ActionDetail actionDetail(UUID studentId, String actionKey) {
        String key = (actionKey == null || actionKey.isBlank()) ? "layup" : actionKey.trim();
        // 个人层面:该动作的练习量/命中率(真实);动作要点/常见错误属知识层,走 RAG,不在此编造
        String note = NOTE + "(要点/常见错误请以知识库检索为准)";
        for (ActionClipRepository.StudentActionStat st : clipRepo.findActionStatsByStudentId(studentId)) {
            if (key.equalsIgnoreCase(st.getActionType())) {
                String title = key + " · 你练了 " + st.getClipCount() + " 次"
                        + (st.getMadeRate() == null ? "" : String.format(",命中率 %.0f%%", st.getMadeRate().doubleValue() * 100));
                return new ActionDetail(key, title, List.of(), List.of(), note);
            }
        }
        return new ActionDetail(key, key + " · 暂无你的该动作记录", List.of(), List.of(), note);
    }

    // ---- helpers ----

    private UUID resolveSession(UUID studentId, UUID trainingSessionId) {
        if (trainingSessionId != null) {
            return trainingSessionId;
        }
        List<UUID> recent = clipRepo.findRecentSessionIdsByStudent(studentId, PageRequest.of(0, 1));
        return recent.isEmpty() ? null : recent.get(0);
    }

    private String recordedAt(TrainingSession s) {
        if (s == null) {
            return null;
        }
        OffsetDateTime t = s.getGeneratedAt() != null ? s.getGeneratedAt() : s.getRecordedAt();
        return t == null ? null : t.toString();
    }

    private String reasonOf(ActionClip c) {
        Object r = c.getScore() == null ? null : c.getScore().get("reason");
        return r == null ? null : String.valueOf(r);
    }

    private String label(OffsetDateTime at) {
        return at == null ? "-" : at.toLocalDate().toString();
    }

    /** 从 stats 取指标:fg_pct / makes / attempts / release.<joint>(→ mean_release_angles.<joint>)。 */
    @SuppressWarnings("unchecked")
    private Double metricValue(Map<String, Object> stats, String metric) {
        if (stats == null) {
            return null;
        }
        if (metric.startsWith("release.")) {
            Object mra = stats.get("mean_release_angles");
            if (mra instanceof Map<?, ?> map) {
                return toDouble(((Map<String, Object>) map).get(metric.substring("release.".length())));
            }
            return null;
        }
        return switch (metric) {
            case "makes" -> toDouble(stats.get("makes"));
            case "attempts" -> toDouble(stats.get("attempts"));
            default -> toDouble(stats.get("fg_pct"));
        };
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

