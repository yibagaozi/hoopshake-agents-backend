package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.domain.entity.ActionClip;
import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.cloud.dto.request.StudentDataRequests.UpdateProfileRequest;
import com.cnsportiot.cloud.dto.response.StudentDataDtos.*;
import com.cnsportiot.cloud.repository.*;
import com.cnsportiot.cloud.service.StudentDataService;
import com.cnsportiot.contracts.common.PageResponse;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.contracts.enums.SessionStatus;
import com.cnsportiot.contracts.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudentDataServiceImpl implements StudentDataService {

    private final ActionClipRepository clipRepo;
    private final SessionAggregateRepository aggRepo;
    private final InstantFeedbackRepository feedbackRepo;
    private final TrainingSessionRepository sessionRepo;
    private final LessonRepository lessonRepo;
    private final StudentRepository studentRepo;

    @Override
    @Transactional(readOnly = true)
    public OverviewResponse overview(UUID studentId) {
        String displayName = studentRepo.findDisplayNameByStudentId(studentId).orElse(null);
        int totalClips = (int) clipRepo.countByStudentId(studentId);
        int totalSessions = (int) clipRepo.countDistinctSessionByStudentId(studentId);

        List<TrainingSession> recent = sessionRepo
                .findSessionsByStudent(studentId, null, null, PageRequest.of(0, 5)).getContent();
        Map<UUID, String> lessonTitles = new LinkedHashMap<>();
        List<SessionBrief> recentBriefs = recent.stream().map(s -> toBrief(s, studentId, lessonTitles)).toList();
        OffsetDateTime lastAt = recent.isEmpty() ? null : recordedAt(recent.get(0));

        // 近 7 天
        OffsetDateTime weekAgo = OffsetDateTime.now().minusDays(7);
        List<TrainingSession> weekSessions = sessionRepo
                .findSessionsByStudent(studentId, weekAgo, null, PageRequest.of(0, 100)).getContent();
        int weekClips = weekSessions.stream().mapToInt(s -> (int) clipRepo.countBySessionIdAndStudentId(s.getId(), studentId)).sum();
        Weekly weekly = new Weekly(weekSessions.size(), weekClips, null);

        List<ActionTypeStat> actionStats = clipRepo.findActionStatsByStudentId(studentId).stream()
                .map(a -> new ActionTypeStat(a.getActionType(), (int) a.getClipCount(),
                        a.getMadeRate() == null ? null : round2(a.getMadeRate().doubleValue())))
                .toList();

        FocusCheckpoint focus = focusCheckpoint(studentId, recent);

        return new OverviewResponse(studentId, displayName, totalSessions, totalClips, lastAt,
                weekly, focus, actionStats, recentBriefs);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SessionBrief> listSessions(UUID studentId, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        Page<TrainingSession> p = sessionRepo.findSessionsByStudent(studentId, from, to, PageResponses.toPageable(page, size));
        Map<UUID, String> lessonTitles = new LinkedHashMap<>();
        return PageResponses.from(p, s -> toBrief(s, studentId, lessonTitles));
    }

    @Override
    @Transactional(readOnly = true)
    public SessionDetail sessionDetail(UUID studentId, UUID sessionId) {
        requireOwnsSession(studentId, sessionId);
        TrainingSession s = sessionRepo.findById(sessionId).orElseThrow(() -> BusinessException.notFound("会话不存在"));
        int clipCount = (int) clipRepo.countBySessionIdAndStudentId(sessionId, studentId);
        List<AggItem> aggs = aggRepo.findBySessionIdAndStudentId(sessionId, studentId).stream()
                .map(a -> new AggItem(a.getActionType(), a.getStats())).toList();
        int feedbackCount = (int) feedbackRepo.countByStudentIdAndSessionId(studentId, sessionId);
        boolean reportReady = s.getStatus() == SessionStatus.REPORT_READY;
        return new SessionDetail(sessionId, s.getLessonId(), lessonTitle(s.getLessonId(), new LinkedHashMap<>()),
                statusName(s), recordedAt(s), clipCount, aggs, feedbackCount, reportReady);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ClipBrief> listClips(UUID studentId, UUID sessionId, String actionType, int page, int size) {
        requireOwnsSession(studentId, sessionId);
        var pageable = PageResponses.toPageable(page, size);
        Page<ActionClip> p = (actionType == null || actionType.isBlank())
                ? clipRepo.findBySessionIdAndStudentId(sessionId, studentId, pageable)
                : clipRepo.findBySessionIdAndStudentIdAndActionType(sessionId, studentId, actionType.trim(), pageable);
        return PageResponses.from(p, this::toClipBrief);
    }

    @Override
    @Transactional(readOnly = true)
    public ClipDetail clipDetail(UUID studentId, UUID clipId) {
        ActionClip c = clipRepo.findById(clipId).orElseThrow(() -> BusinessException.notFound("片段不存在"));
        if (!studentId.equals(c.getStudentId())) {
            throw BusinessException.dataScopeDenied();
        }
        return new ClipDetail(c.getId(), c.getSessionId(), c.getClipIndex(), c.getActionType(),
                c.getStartMs(), c.getEndMs(), c.getReleaseMs(), c.getAnchorCamera(), c.getZoneId(),
                c.getShotMade(), c.getScore(), c.getPhases(), c.getMotionRange(), c.getMotionUri());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<FeedbackItem> listFeedback(UUID studentId, UUID sessionId, String severity, int page, int size) {
        requireOwnsSession(studentId, sessionId);
        var pageable = PageResponses.toPageable(page, size);
        FeedbackSeverity sev = parseSeverity(severity);
        Page<InstantFeedback> p = (sev == null)
                ? feedbackRepo.findByStudentIdAndSessionId(studentId, sessionId, pageable)
                : feedbackRepo.findByStudentIdAndSessionIdAndSeverity(studentId, sessionId, sev, pageable);
        return PageResponses.from(p, this::toFeedbackItem);
    }

    @Override
    @Transactional(readOnly = true)
    public TrendResponse trend(UUID studentId, String actionType, String metric, int limit) {
        String m = (metric == null || metric.isBlank()) ? "fg_pct" : metric.trim();
        List<TrendPoint> pts = new ArrayList<>();
        for (Object[] row : aggRepo.findWithTimeByStudent(studentId)) {
            SessionAggregate agg = (SessionAggregate) row[0];
            OffsetDateTime at = (OffsetDateTime) row[1];
            if (actionType != null && !actionType.isBlank() && !actionType.equalsIgnoreCase(agg.getActionType())) {
                continue;
            }
            Double v = metricValue(agg.getStats(), m);
            if (v != null) {
                pts.add(new TrendPoint(agg.getSessionId(), at, round2(v)));
            }
        }
        if (limit > 0 && pts.size() > limit) {
            pts = pts.subList(pts.size() - limit, pts.size());
        }
        return new TrendResponse(actionType, m, pts);
    }

    @Override
    @Transactional
    public ProfileResponse updateProfile(UUID studentId, UpdateProfileRequest req) {
        Student s = studentRepo.findById(studentId).orElseThrow(() -> BusinessException.notFound("学生档案不存在"));
        if (req.dominantHand() != null) {
            s.setDominantHand(req.dominantHand());
        }
        if (req.heightCm() != null) {
            s.setHeightCm(req.heightCm());
        }
        if (req.legLengthCm() != null) {
            s.setLegLengthCm(req.legLengthCm());
        }
        studentRepo.save(s);
        String displayName = studentRepo.findDisplayNameByStudentId(studentId).orElse(null);
        return new ProfileResponse(s.getId(), s.getStudentNo(), displayName,
                s.getDominantHand() == null ? null : s.getDominantHand().name(),
                s.getHeightCm(), s.getLegLengthCm(), s.getGradeBand());
    }

    // ---- helpers ----

    private void requireOwnsSession(UUID studentId, UUID sessionId) {
        if (!clipRepo.existsBySessionIdAndStudentId(sessionId, studentId)) {
            throw BusinessException.dataScopeDenied();
        }
    }

    private SessionBrief toBrief(TrainingSession s, UUID studentId, Map<UUID, String> lessonTitles) {
        int clipCount = (int) clipRepo.countBySessionIdAndStudentId(s.getId(), studentId);
        return new SessionBrief(s.getId(), s.getLessonId(), lessonTitle(s.getLessonId(), lessonTitles),
                statusName(s), recordedAt(s), clipCount, null);
    }

    private ClipBrief toClipBrief(ActionClip c) {
        return new ClipBrief(c.getId(), c.getSessionId(), c.getClipIndex(), c.getActionType(),
                c.getStartMs(), c.getEndMs(), c.getReleaseMs(), c.getAnchorCamera(), c.getZoneId(),
                c.getShotMade(), c.getScore());
    }

    private FeedbackItem toFeedbackItem(InstantFeedback f) {
        return new FeedbackItem(f.getId(), f.getClipId(), f.getOccurredAt(), f.getTimestampMs(),
                f.getActionType(), f.getCheckpointId(),
                f.getSeverity() == null ? null : f.getSeverity().name(),
                f.getCueText(), f.getMeasured(), f.getConfidence(), f.getSourceCamera());
    }

    private FocusCheckpoint focusCheckpoint(UUID studentId, List<TrainingSession> recent) {
        if (recent.isEmpty()) {
            return null;
        }
        UUID latest = recent.get(0).getId();
        String worst = null;
        double worstPass = 2.0;
        for (InstantFeedbackRepository.CheckpointAgg a : feedbackRepo.checkpointAgg(studentId, latest)) {
            double pass = a.getTotal() == 0 ? 1.0 : 1.0 - (double) a.getMajor() / a.getTotal();
            if (pass < worstPass) {
                worstPass = pass;
                worst = a.getCheckpointId();
            }
        }
        return worst == null ? null : new FocusCheckpoint(worst, worst, round2(worstPass), 0.0);
    }

    private String lessonTitle(UUID lessonId, Map<UUID, String> cache) {
        if (lessonId == null) {
            return null;
        }
        return cache.computeIfAbsent(lessonId,
                id -> lessonRepo.findById(id).map(Lesson::getTitle).orElse(null));
    }

    private static String statusName(TrainingSession s) {
        return s.getStatus() == null ? null : s.getStatus().name();
    }

    private static OffsetDateTime recordedAt(TrainingSession s) {
        return s.getGeneratedAt() != null ? s.getGeneratedAt() : s.getRecordedAt();
    }

    private static FeedbackSeverity parseSeverity(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return FeedbackSeverity.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Double metricValue(Map<String, Object> stats, String metric) {
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

