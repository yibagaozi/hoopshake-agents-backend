package com.cnsportiot.cloud.harness.tool.port;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表:启动期收集所有 {@link AgentTool} bean 并按 {@link ToolSpec#name()} 建索引
 * 重名直接抛错(function name 必须唯一,否则模型无法区分)
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> byName;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> m = new LinkedHashMap<>();
        for (AgentTool t : tools) {
            String name = t.spec().name();
            AgentTool prev = m.put(name, t);
            if (prev != null) {
                throw new IllegalStateException("工具重名: " + name
                        + "(" + prev.getClass().getName() + " vs " + t.getClass().getName() + ")");
            }
        }
        this.byName = Map.copyOf(m);
        log.info("已注册 {} 个 Agent 工具: {}", byName.size(), byName.keySet());
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 全部工具 */
    public List<AgentTool> all() {
        return List.copyOf(byName.values());
    }
}

