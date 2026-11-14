package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):Agent 近窗表现——降级率、RAG 命中率、平均回答长度、工具 OK/DENY/ERROR 与错误率。
 * 委托 {@link OpsService#agentQuality(int)}。用于 Agent 质量诊断(降级/工具错误归因)
 */
@Component
public class GetAgentQualityTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_agent_quality",
            "Agent 近窗表现聚合:已回答轮数、降级轮数与降级率、RAG 命中率、平均回答字数、工具调用 OK/拒绝/错误数与"
                    + "工具错误率。用于诊断对话质量下降(是降级?工具越权?)。windowHours 可选,缺省用系统默认窗口。",
            "正在聚合 Agent 近窗表现…",
            """
            {"type":"object","properties":{
              "windowHours":{"type":"integer","description":"回溯窗口(小时),缺省用系统默认","minimum":1,"maximum":720}
            }}""");

    private final OpsService ops;
    private final OpsProperties props;

    public GetAgentQualityTool(OpsService ops, OpsProperties props) {
        this.ops = ops;
        this.props = props;
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
        int windowHours = OpsToolArgs.getInt(args, "windowHours", props.getAgent().getDefaultWindowHours());
        return ops.agentQuality(windowHours);
    }
}
