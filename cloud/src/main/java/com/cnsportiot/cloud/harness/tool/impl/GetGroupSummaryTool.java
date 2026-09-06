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
 * 群体聚合:一组学生某指标的均值/分布/离群/走势。目标可用
 * studentIds / lessonId(整班)/ lessonIds(多课次=课程)/ classCode(班级号),归属由教师闸校验
 */
@Component
public class GetGroupSummaryTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_group_summary",
            "对一组学生某指标做聚合(均值/中位/极值/后进离群/走势),用于整班、整门课程(多课次)或多名学生的横向分析。"
                    + "目标四选一或组合:studentIds、lessonId、lessonIds、classCode。metric 默认 checkpoint_pass_rate。",
            "正在汇总该群体的指标…",
            """
            {"type":"object","properties":{
              "studentIds":{"type":"array","items":{"type":"string"},"description":"学生 id 列表"},
              "lessonId":{"type":"string","description":"整班:一节课的 id"},
              "lessonIds":{"type":"array","items":{"type":"string"},"description":"课程=多课次:课程 id 列表"},
              "classCode":{"type":"string","description":"按班级号聚合"},
              "metric":{"type":"string","description":"指标名,默认 checkpoint_pass_rate"},
              "weeks":{"type":"integer","description":"走势回溯周数,默认 8","minimum":2,"maximum":12}
            }}""");

    private final TeacherAnalyticsPort analytics;
    private final LessonEnrollmentRepository enrollRepo;

    public GetGroupSummaryTool(TeacherAnalyticsPort analytics, LessonEnrollmentRepository enrollRepo) {
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
        String metric = ToolArgs.getString(args, "metric");
        int weeks = ToolArgs.getInt(args, "weeks", 8);
        return analytics.groupSummary(studentIds, metric, weeks);
    }
}

