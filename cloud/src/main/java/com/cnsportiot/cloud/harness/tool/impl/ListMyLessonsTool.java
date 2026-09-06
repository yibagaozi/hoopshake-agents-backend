package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 发现层:列出当前教师名下课程。范围来自 ctx.accountId(),不接受入参指定他人 */
@Component
public class ListMyLessonsTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "list_my_lessons",
            "列出当前教师名下的课程(课程 id、标题、班级号、动作类型、排期、人数)。"
                    + "分析某节课/某班/整门课程前,先用它拿到 lessonId。",
            "正在查看你的课程列表…",
            "{\"type\":\"object\",\"properties\":{}}");

    private final LessonRepository lessonRepo;
    private final LessonEnrollmentRepository enrollRepo;

    public ListMyLessonsTool(LessonRepository lessonRepo, LessonEnrollmentRepository enrollRepo) {
        this.lessonRepo = lessonRepo;
        this.enrollRepo = enrollRepo;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ScopeKind scope() {
        return ScopeKind.TEACHER;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        UUID teacher = ctx.accountId();
        List<Lesson> lessons = lessonRepo.findByTeacherIdOrderByScheduledAtDesc(teacher);
        return lessons.stream().map(l -> new LessonBrief(
                l.getId(), l.getTitle(), l.getClassCode(),
                l.getActionTypes(), l.getScheduledAt(),
                l.getStatus() == null ? null : l.getStatus().name(),
                enrollRepo.countByLessonId(l.getId()))).toList();
    }

    public record LessonBrief(
            UUID lessonId, String title, String classCode,
            List<String> actionTypes, OffsetDateTime scheduledAt,
            String status, long studentCount) {}
}

