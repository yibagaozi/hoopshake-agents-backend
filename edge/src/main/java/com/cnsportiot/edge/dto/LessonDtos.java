package com.cnsportiot.edge.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 选课相关 DTO */
public final class LessonDtos {

    private LessonDtos() {
    }

    /** POST /local/lesson/select —— 字段取自云端 §5.2 的 LessonResponse */
    public record SelectLessonRequest(
            @NotBlank(message = "lessonId 不能为空") String lessonId,
            String title,
            String classCode,
            List<String> actionTypes,
            List<String> enabledCheckpoints,
            String zoneConfigRef) {
    }

    /** 选课结果,附带同步下来的名单概览 */
    public record LessonSelectResponse(
            UUID lessonId,
            String title,
            String classCode,
            List<String> actionTypes,
            List<String> enabledCheckpoints,
            String zoneConfigRef,
            OffsetDateTime selectedAt,
            /** 名单总人数 */
            int rosterTotal,
            /** 已采集 ReID 特征人数;差额即课上需现场注册的人 */
            int galleryReadyCount) {
    }

    /** GET /local/lesson —— 当前选定课程;未选课时 lessonId 为 null */
    public record LessonContextResponse(
            UUID lessonId,
            String title,
            String classCode,
            List<String> actionTypes,
            List<String> enabledCheckpoints,
            String zoneConfigRef,
            OffsetDateTime selectedAt) {

        public static LessonContextResponse empty() {
            return new LessonContextResponse(null, null, null, List.of(), List.of(), null, null);
        }
    }
}

