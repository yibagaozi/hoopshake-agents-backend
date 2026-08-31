package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 单次训练小结。trainingSessionId 归属由 StudentScopeGuardHook 校验;省略取最近一次 */
@Component
public class GetSessionSummaryTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_session_summary",
            "获取某一次训练的小结(命中率、关键发现、教练点评)。学生问'这次/某次训练总结'时调用;不传 id 则取最近一次。",
            "正在整理这次训练的小结…",
            """
            {"type":"object","properties":{
              "trainingSessionId":{"type":"string","description":"训练会话 id(UUID);省略取最近一次"}
            }}""");

    private final StudentDataPort studentData;

    public GetSessionSummaryTool(StudentDataPort studentData) {
        this.studentData = studentData;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        return studentData.sessionSummary(ctx.studentId(), ToolArgs.getUuid(args, "trainingSessionId"));
    }
}

