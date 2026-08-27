package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 即时反馈流水。trainingSessionId 归属由 Hook 校验 */
@Component
public class GetInstantFeedbackLogTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_instant_feedback_log",
            "获取某次训练过程中的即时反馈流水(时间点、提示语、严重度)。学生问'训练时提示了什么/哪里错了'时调用;不传 id 取最近一次。",
            "正在调取这次训练的即时反馈…",
            """
            {"type":"object","properties":{
              "trainingSessionId":{"type":"string","description":"训练会话 id(UUID);省略取最近一次"}
            }}""");

    private final StudentDataPort studentData;

    public GetInstantFeedbackLogTool(StudentDataPort studentData) {
        this.studentData = studentData;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        return studentData.instantFeedbackLog(ctx.studentId(), ToolArgs.getUuid(args, "trainingSessionId"));
    }
}

