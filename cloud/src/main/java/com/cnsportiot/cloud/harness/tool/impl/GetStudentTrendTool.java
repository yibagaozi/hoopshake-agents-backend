package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 个体层:某学生某指标的历史走势。studentId 归属由教师闸校验 */
@Component
public class GetStudentTrendTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_student_trend",
            "获取指定学生某指标的历史走势(按周)。metric 见 checkpoint_pass_rate / safety_alert_rate / "
                    + "attendance / checkpoint.<name> / action.<key>.<indicator>;weeks 默认 8。用于分析单人进步。",
            "正在查看该学生的历史走势…",
            """
            {"type":"object","properties":{
              "studentId":{"type":"string","description":"学生 id"},
              "metric":{"type":"string","description":"指标名,默认 checkpoint_pass_rate"},
              "weeks":{"type":"integer","description":"回溯周数,默认 8,最多 12","minimum":2,"maximum":12}
            },"required":["studentId"]}""");

    private final TeacherAnalyticsPort analytics;

    public GetStudentTrendTool(TeacherAnalyticsPort analytics) {
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
            throw new BusinessException(ErrorCode.PARAM_INVALID, "studentId 缺失或非法");
        }
        String metric = ToolArgs.getString(args, "metric");
        int weeks = ToolArgs.getInt(args, "weeks", 8);
        return analytics.studentTrend(studentId, metric, weeks);
    }
}

