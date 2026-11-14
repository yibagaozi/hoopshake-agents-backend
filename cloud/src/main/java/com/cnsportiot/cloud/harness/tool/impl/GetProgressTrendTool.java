package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 进步趋势 */
@Component
public class GetProgressTrendTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_progress_trend",
            "获取当前学生某指标随时间的进步趋势(如命中率)。学生问'我有没有进步/最近提升了吗'时调用。",
            "正在计算你的进步趋势…",
            """
            {"type":"object","properties":{
              "metric":{"type":"string","description":"指标名,默认'命中率'"},
              "weeks":{"type":"integer","description":"回溯周数,默认 4,最多 8","minimum":1,"maximum":8}
            }}""");

    private final StudentDataPort studentData;

    public GetProgressTrendTool(StudentDataPort studentData) {
        this.studentData = studentData;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        String metric = ToolArgs.getString(args, "metric");
        int weeks = ToolArgs.getInt(args, "weeks", 4);
        return studentData.progressTrend(ctx.studentId(), metric, weeks);
    }
}

