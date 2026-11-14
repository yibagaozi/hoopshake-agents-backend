package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort;
import com.cnsportiot.contracts.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 个体层:某学生单次训练指标(默认最近一次)。studentId 归属由教师闸校验 */
@Component
public class GetStudentSessionTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_student_session",
            "获取指定学生某次训练的指标(检查点通过率、片段数、主要问题)。trainingSessionId 省略取最近一次。"
                    + "用于分析单人单次表现。",
            "正在查看该学生的单次训练…",
            """
            {"type":"object","properties":{
              "studentId":{"type":"string","description":"学生 id(来自 list_lesson_students / resolve_student)"},
              "trainingSessionId":{"type":"string","description":"训练会话 id,省略取最近一次"}
            },"required":["studentId"]}""");

    private final TeacherAnalyticsPort analytics;

    public GetStudentSessionTool(TeacherAnalyticsPort analytics) {
        this.analytics = analytics;
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
        UUID studentId = ToolArgs.getUuid(args, "studentId");
        if (studentId == null) {
            throw new BusinessException(com.cnsportiot.contracts.error.ErrorCode.PARAM_INVALID,
                    "studentId 缺失或非法");
        }
        UUID sessionId = ToolArgs.getUuid(args, "trainingSessionId");
        return analytics.studentSession(studentId, sessionId);
    }
}
