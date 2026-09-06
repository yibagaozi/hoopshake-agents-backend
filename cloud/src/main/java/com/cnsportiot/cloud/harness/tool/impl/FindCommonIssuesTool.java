package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 群体共性问题:一组学生里高频未达的检查点/安全项排名(含受影响人数与改善/恶化方向)。
 * 目标同 get_group_summary(studentIds / lessonId / lessonIds / classCode),归属由教师闸校验
 */
@Component
public class FindCommonIssuesTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "find_common_issues",
            "找出一组学生的共性问题(高频未达检查点/安全项 + 受影响人数 + 改善/恶化),用于班级/课程的集体薄弱点诊断。"
                    + "目标:studentIds、lessonId、lessonIds、classCode 之一或组合。",
            "正在归纳该群体的共性问题…",
            """
            {"type":"object","properties":{
              "studentIds":{"type":"array","items":{"type":"string"}},
              "lessonId":{"type":"string"},
              "lessonIds":{"type":"array","items":{"type":"string"}},
              "classCode":{"type":"string"},
              "weeks":{"type":"integer","description":"回溯周数,默认 8","minimum":2,"maximum":12}
            }}""");

    private final TeacherAnalyticsPort analytics;
    private final LessonEnrollmentRepository enrollRepo;

    public FindCommonIssuesTool(TeacherAnalyticsPort analytics, LessonEnrollmentRepository enrollRepo) {
        this.analytics = analytics;
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
        List<UUID> studentIds = TeacherTargets.resolve(args, ctx.accountId(), enrollRepo);
        if (studentIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "未解析到任何学生;请提供 studentIds / lessonId / lessonIds / classCode 之一");
        }
        int weeks = ToolArgs.getInt(args, "weeks", 8);
        return analytics.commonIssues(studentIds, weeks);
    }
}
