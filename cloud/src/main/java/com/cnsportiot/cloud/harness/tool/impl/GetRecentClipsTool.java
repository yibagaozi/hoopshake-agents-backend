package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 最近训练片段列表。身份取 ctx,不接受入参指定他人 */
@Component
public class GetRecentClipsTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_recent_clips",
            "获取当前学生最近的训练片段列表(动作、命中数、主要问题)。当学生问'我最近练得怎么样/上次训练'时调用。",
            "正在查看你最近的训练片段…",
            """
            {"type":"object","properties":{
              "limit":{"type":"integer","description":"返回条数,默认 3,最多 3","minimum":1,"maximum":3}
            }}""");

    private final StudentDataPort studentData;

    public GetRecentClipsTool(StudentDataPort studentData) {
        this.studentData = studentData;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        int limit = ToolArgs.getInt(args, "limit", 3);
        return studentData.recentClips(ctx.studentId(), limit);
    }
}

