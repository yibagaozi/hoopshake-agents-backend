package com.cnsportiot.cloud.harness.llm;

/**
 * 模型档位(见 docs/agent/agent-system-design.md §5.2)。
 * Mode 定上限,Agent 在上限内取值;LlmGateway 把档位映射到具体 GLM 型号。
 */
public enum Tier {
    FAST,
    STANDARD,
    ADVANCED
}
