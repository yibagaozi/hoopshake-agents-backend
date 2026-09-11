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
 * 发现层:在教师名下学生里按姓名/学号模糊找人 studentId
 * 查询本身即限定在 ctx.accountId() 的课程范围内,天然不越界
 */
@Component
public class ResolveStudentTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "resolve_student",
            "在当前教师名下学生中按姓名或学号模糊查找,返回匹配的 studentId 列表。"
                    + "当教师用姓名/学号(而非 id)指名某学生时先调用它。",
            "正在按姓名/学号查找学生…",
            """
            {"type":"object","properties":{
              "keyword":{"type":"string","description":"姓名或学号的一部分"}
            },"required":["keyword"]}""");

    private final LessonEnrollmentRepository enrollRepo;

    public ResolveStudentTool(LessonEnrollmentRepository enrollRepo) {
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
        String keyword = ToolArgs.getString(args, "keyword");
        if (keyword == null) {
            return List.of();
        }
        return enrollRepo.searchStudentsUnderTeacher(ctx.accountId(), keyword).stream()
                .map(v -> new StudentRef(v.getStudentId(), v.getStudentNo(), v.getDisplayName()))
                .toList();
    }

    public record StudentRef(UUID studentId, String studentNo, String displayName) {}
}

