package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发现层:列出某课程的学生名单。lessonId 的归属由 {@code TeacherScopeGuardHook} 前置校验
 */
@Component
public class ListLessonStudentsTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "list_lesson_students",
            "列出指定课程(lessonId)的学生名单(studentId、学号、姓名)。"
                    + "分析整班/多名学生前,用它把 lessonId 展开成 studentId 列表。",
            "正在查看该课程的学生名单…",
            """
            {"type":"object","properties":{
              "lessonId":{"type":"string","description":"课程 id(来自 list_my_lessons)"}
            },"required":["lessonId"]}""");

    private final LessonEnrollmentRepository enrollRepo;

    public ListLessonStudentsTool(LessonEnrollmentRepository enrollRepo) {
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
        UUID lessonId = ToolArgs.getUuid(args, "lessonId");
        if (lessonId == null) {
            return List.of();
        }
        return enrollRepo.findEnrollmentView(lessonId).stream()
                .map(v -> new StudentRef(v.getStudentId(), v.getStudentNo(), v.getDisplayName()))
                .toList();
    }

    public record StudentRef(UUID studentId, String studentNo, String displayName) {}
}

