package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.InstantFeedback;
import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.entity.TrainingSession;
import com.cnsportiot.cloud.dto.response.LessonDtos.SafetyAlertResponse;
import com.cnsportiot.cloud.dto.response.SummaryDtos.*;
import com.cnsportiot.cloud.repository.*;
import com.cnsportiot.cloud.service.TeacherSummaryService;
import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.contracts.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherSummaryServiceImpl implements TeacherSummaryService {

    private final TrainingSessionRepository trainingSessionRepository;
    private final LessonRepository lessonRepository;
    private final LessonEnrollmentRepository lessonEnrollmentRepository;
    private final ActionClipRepository actionClipRepository;
    private final InstantFeedbackRepository instantFeedbackRepository;
    private final StudentRepository studentRepository;

    @Override
    @Transactional(readOnly = true)
    public ClassSummaryResponse getSessionSummary(UUID sessionId, UUID teacherAccountId) {
        TrainingSession session = trainingSessionRepository.findById(sessionId)
                .orElseThrow(() -> BusinessException.notFound("TrainingSession " + sessionId));

        Lesson lesson = lessonRepository.findById(session.getLessonId())
                .orElseThrow(() -> BusinessException.notFound("Lesson " + session.getLessonId()));

        if (!lesson.getTeacherId().equals(teacherAccountId)) {
            throw BusinessException.dataScopeDenied();
        }

        List<UUID> enrolledIds = lessonEnrollmentRepository.findStudentIdsByLessonId(lesson.getId());
        List<UUID> presentIds = actionClipRepository.findDistinctStudentIdsBySessionId(sessionId);
        var attendance = new Attendance(presentIds.size(), enrolledIds.size());

        long totalClips = actionClipRepository.countBySessionId(sessionId);
        BigDecimal avgMadeRate = actionClipRepository.avgMadeRateBySessionId(sessionId);

        Set<UUID> allStudentIds = new HashSet<>(enrolledIds);
        allStudentIds.addAll(presentIds);
        Map<UUID, String> displayNames = resolveDisplayNames(allStudentIds);

        List<SafetyAlertResponse> safetyAlerts = buildSafetyAlerts(sessionId, displayNames);
        List<CheckpointDistributionItem> checkpointDistribution = buildCheckpointDistribution(sessionId);
        List<ClassSummaryStudentRow> studentRows = buildStudentRows(sessionId, presentIds, displayNames);

        return new ClassSummaryResponse(
                sessionId,
                lesson.getId(),
                lesson.getTitle(),
                lesson.getClassCode(),
                session.getStatus(),
                session.getRecordedAt(),
                lesson.getDurationMinutes(),
                attendance,
                totalClips,
                avgMadeRate != null ? avgMadeRate : BigDecimal.ZERO,
                safetyAlerts.size(),
                checkpointDistribution,
                safetyAlerts,
                studentRows,
                null);
    }

    private Map<UUID, String> resolveDisplayNames(Set<UUID> studentIds) {
        if (studentIds.isEmpty()) return Map.of();
        return studentRepository.findRefsByIdIn(studentIds).stream()
                .collect(Collectors.toMap(
                        StudentRepository.StudentRef::getStudentId,
                        ref -> ref.getDisplayName() != null ? ref.getDisplayName() : ref.getStudentNo(),
                        (a, b) -> a));
    }

    private List<SafetyAlertResponse> buildSafetyAlerts(UUID sessionId, Map<UUID, String> displayNames) {
        return instantFeedbackRepository.findBySessionIdAndSeverity(sessionId, FeedbackSeverity.MAJOR)
                .stream()
                .map(fb -> new SafetyAlertResponse(
                        fb.getId(), fb.getStudentId(),
                        displayNames.getOrDefault(fb.getStudentId(), ""),
                        fb.getActionType(), fb.getCheckpointId(),
                        fb.getSeverity(), fb.getCueText(), fb.getOccurredAt()))
                .toList();
    }

    private List<CheckpointDistributionItem> buildCheckpointDistribution(UUID sessionId) {
        var counts = instantFeedbackRepository.countBySessionIdGroupByCheckpoint(sessionId);
        Set<String> safetyCheckpoints = instantFeedbackRepository
                .findBySessionIdAndSeverity(sessionId, FeedbackSeverity.MAJOR)
                .stream()
                .map(InstantFeedback::getCheckpointId)
                .collect(Collectors.toSet());

        return counts.stream()
                .map(c -> new CheckpointDistributionItem(
                        c.getCheckpointId(), c.getCheckpointId(),
                        c.getCnt(), safetyCheckpoints.contains(c.getCheckpointId())))
                .sorted(Comparator.comparingLong(CheckpointDistributionItem::count).reversed())
                .toList();
    }

    private List<ClassSummaryStudentRow> buildStudentRows(
            UUID sessionId, List<UUID> presentIds, Map<UUID, String> displayNames) {

        Map<UUID, Long> clipCounts = actionClipRepository.countBySessionIdGroupByStudentId(sessionId)
                .stream()
                .collect(Collectors.toMap(
                        ActionClipRepository.StudentClipCount::getStudentId,
                        ActionClipRepository.StudentClipCount::getCnt));

        Map<UUID, InstantFeedbackRepository.StudentCheckpointCount> keyCheckpoints =
                instantFeedbackRepository.countBySessionIdGroupByStudentAndCheckpoint(sessionId)
                        .stream()
                        .collect(Collectors.toMap(
                                InstantFeedbackRepository.StudentCheckpointCount::getStudentId,
                                Function.identity(),
                                (a, b) -> a.getCnt() >= b.getCnt() ? a : b));

        Set<UUID> presentSet = new HashSet<>(presentIds);

        return displayNames.entrySet().stream()
                .map(entry -> {
                    UUID sid = entry.getKey();
                    var keyCheckpoint = keyCheckpoints.get(sid);
                    return new ClassSummaryStudentRow(
                            sid,
                            entry.getValue(),
                            clipCounts.getOrDefault(sid, 0L),
                            keyCheckpoint != null ? keyCheckpoint.getCheckpointId() : null,
                            keyCheckpoint != null ? keyCheckpoint.getCheckpointId() : null,
                            List.of(),
                            presentSet.contains(sid));
                })
                .sorted(Comparator.comparingLong(ClassSummaryStudentRow::clipCount).reversed())
                .toList();
    }
}

