package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):读**运行时活配置**——SSE 硬超时、并发舱壁、/ask 限流、熔断、退避、边缘健康阈值、
 * Agent 默认窗口。读 {@link AgentProperties}/{@link OpsProperties} 活值,不进 RAG,
 * 会与线上 env 覆盖后的真实值不一致;要"当前配置"必须读运行时)。回答"限流阈值是多少""熔断几次打开"用这个
 */
@Component
public class GetRuntimeConfigTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "get_runtime_config",
            "读当前运行时配置活值:SSE 硬超时、LLM 流并发舱壁上限、/ask 令牌桶限流(开关/burst/每分钟回填)、熔断"
                    + "(开关/失败阈值/冷却)、退避(基准/上限/抖动)、边缘健康阈值(online/offline)、Agent 默认窗口。"
                    + "回答\"当前阈值/配置是多少\"用这个,不要凭记忆或文档快照报数字。无参。",
            "正在读取运行时配置…",
            "{\"type\":\"object\",\"properties\":{}}");

    private final AgentProperties agent;
    private final OpsProperties ops;

    public GetRuntimeConfigTool(AgentProperties agent, OpsProperties ops) {
        this.agent = agent;
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
        var r = agent.getResilience();
        return new RuntimeConfig(
                agent.isEnabled(),
                agent.getMaxRetries(),
                new Sse(r.getSse().getHardTimeoutMillis()),
                new Bulkhead(r.getBulkhead().getMaxConcurrentStreams()),
                new RateLimit(r.getRateLimit().isEnabled(), r.getRateLimit().getBurst(),
                        r.getRateLimit().getRefillPerMinute()),
                new Circuit(r.getCircuit().isEnabled(), r.getCircuit().getFailureThreshold(),
                        r.getCircuit().getOpenMillis()),
                new Backoff(r.getBackoff().getBaseMillis(), r.getBackoff().getMaxMillis(),
                        r.getBackoff().isJitter()),
                new Edge(ops.getEdge().getOnlineWithin().toSeconds(), ops.getEdge().getOfflineAfter().toSeconds()),
                ops.getAgent().getDefaultWindowHours());
    }

    /** 运行时配置活值快照(可 Jackson 序列化)。 */
    public record RuntimeConfig(
            boolean llmEnabled,
            int maxRetries,
            Sse sse,
            Bulkhead bulkhead,
            RateLimit askRateLimit,
            Circuit circuit,
            Backoff backoff,
            Edge edge,
            int agentDefaultWindowHours) {}

    public record Sse(long hardTimeoutMillis) {}

    public record Bulkhead(int maxConcurrentStreams) {}

    public record RateLimit(boolean enabled, int burst, int refillPerMinute) {}

    public record Circuit(boolean enabled, int failureThreshold, long openMillis) {}

    public record Backoff(long baseMillis, long maxMillis, boolean jitter) {}

    public record Edge(long onlineWithinSeconds, long offlineAfterSeconds) {}
}
