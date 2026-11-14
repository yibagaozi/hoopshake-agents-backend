package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 某动作的要点与常见错误。偏知识,不做资源归属校验
 */
@Component
public class GetActionDetailTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_action_detail",
            "获取某个篮球动作的技术要点与常见错误(如原地罚篮、三步上篮)。学生问'XX动作怎么做/要点是什么'时调用。",
            "正在查动作要点…",
            """
            {"type":"object","properties":{
              "actionKey":{"type":"string","description":"动作标识或名称,如 freethrow / 原地罚篮"}
            },"required":["actionKey"]}""");

    private final StudentDataPort studentData;

    public GetActionDetailTool(StudentDataPort studentData) {
        this.studentData = studentData;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        return studentData.actionDetail(ctx.studentId(), ToolArgs.getString(args, "actionKey"));
    }
}

