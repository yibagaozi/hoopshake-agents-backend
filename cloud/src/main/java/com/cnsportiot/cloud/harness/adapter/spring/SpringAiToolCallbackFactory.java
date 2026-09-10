package com.cnsportiot.cloud.harness.adapter.spring;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolEventListener;
import com.cnsportiot.cloud.harness.tool.ToolResult;
import com.cnsportiot.cloud.harness.tool.ToolRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 harness 的 {@link AgentTool} 翻译成 Spring AI {@link ToolCallback}
 * 每次对话现构:ToolCallback 绑定当次的 {@link ToolContext}(权威 studentId)与事件监听器
 * 模型触发调用时,Spring AI 在内部 ReAct 循环中回调 {@link HarnessToolCallback#call}
 * 我们转交 {@link ToolRunner}(Hook 闸门 + 审计),再把结果序列化回注给模型
 */
@Slf4j
@Component
public class SpringAiToolCallbackFactory {

    private final ToolRunner toolRunner;
    private final ObjectMapper objectMapper;

    public SpringAiToolCallbackFactory(ToolRunner toolRunner, ObjectMapper objectMapper) {
        this.toolRunner = toolRunner;
        this.objectMapper = objectMapper;
    }

    public List<ToolCallback> build(List<AgentTool> tools, ToolContext ctx, ToolEventListener listener) {
        List<ToolCallback> out = new ArrayList<>();
        if (tools != null) {
            for (AgentTool t : tools) {
                out.add(new HarnessToolCallback(t, ctx, listener == null ? ToolEventListener.NOOP : listener));
            }
        }
        return out;
    }

    /** 单个工具的 Spring AI 回调实现 */
    private final class HarnessToolCallback implements ToolCallback {

        private final AgentTool tool;
        private final ToolContext ctx;
        private final ToolEventListener listener;

        HarnessToolCallback(AgentTool tool, ToolContext ctx, ToolEventListener listener) {
            this.tool = tool;
            this.ctx = ctx;
            this.listener = listener;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(tool.spec().name())
                    .description(tool.spec().description())
                    .inputSchema(tool.spec().inputSchema())
                    .build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> args = parseArgs(toolInput);
            ToolResult result = toolRunner.run(tool.spec().name(), args, ctx, listener);
            return serialize(result);
        }

        private Map<String, Object> parseArgs(String toolInput) {
            if (toolInput == null || toolInput.isBlank()) {
                return new LinkedHashMap<>();
            }
            try {
                Map<String, Object> m = objectMapper.readValue(toolInput, new TypeReference<>() { });
                return m == null ? new LinkedHashMap<>() : m;
            } catch (Exception e) {
                log.warn("工具入参解析失败 tool={} input={}", tool.spec().name(), toolInput, e);
                return new LinkedHashMap<>();
            }
        }

        /** 结果回注给模型:成功回 data 的 JSON;越权/失败回可读短消息,让模型据此道歉/改口 */
        private String serialize(ToolResult result) {
            try {
                return switch (result.status()) {
                    case OK -> objectMapper.writeValueAsString(result.data());
                    case DENIED -> "{\"error\":\"denied\",\"message\":\"无权访问他人数据\"}";
                    case ERROR -> "{\"error\":\"failed\",\"message\":\""
                            + (result.message() == null ? "工具执行失败" : result.message()) + "\"}";
                };
            } catch (Exception e) {
                log.error("工具结果序列化失败 tool={}", result.name(), e);
                return "{\"error\":\"serialize_failed\"}";
            }
        }
    }
}
