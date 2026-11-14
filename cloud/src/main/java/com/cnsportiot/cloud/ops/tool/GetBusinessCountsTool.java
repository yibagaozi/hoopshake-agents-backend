package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):业务量——课程(总/计划/进行/结束)、学生、教师、训练场次、数据量
 * (片段/即时反馈/对话/消息/知识文档)。委托 {@link OpsService#business()}。用于容量/规模问答
 */
@Component
public class GetBusinessCountsTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_business_counts",
            "业务量统计:学生数、教师数、课程数(总/计划/进行/结束)、训练场次、数据量(动作片段/即时反馈/"
                    + "对话会话/消息/知识文档)。回答规模/容量类问题。无参。",
            "正在统计业务量…",
            "{\"type\":\"object\",\"properties\":{}}");

    private final OpsService ops;

    public GetBusinessCountsTool(OpsService ops) {
        this.ops = ops;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ScopeKind scope() {
        return ScopeKind.OPS;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        return ops.business();
    }
}
