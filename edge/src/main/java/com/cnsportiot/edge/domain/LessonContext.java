package com.cnsportiot.edge.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 当前选定的课程上下文 */
public record LessonContext(
        UUID lessonId,
        String title,
        String classCode,
        List<String> actionTypes,
        List<String> enabledCheckpoints,
        String zoneConfigRef,
        OffsetDateTime selectedAt) {
}

