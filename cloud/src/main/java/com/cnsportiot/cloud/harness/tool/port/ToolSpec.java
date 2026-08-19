package com.cnsportiot.cloud.harness.tool.port;

/**
 * 工具的声明式规格。框架无关:{@code adapter/spring} 把它翻译成 Spring AI 的
 * {@code ToolDefinition}/{@code ToolCallback},业务层与 LLM 之外的调用方只认本类型。
 *
 * @param name         函数名(function-calling 用;需稳定、无空格)
 * @param description  给模型看的用途描述(决定模型何时调用,写清楚"什么时候用、返回什么")
 * @param inputSchema  入参的 JSON Schema 字符串(模型据此生成参数);无参工具给 {@code {"type":"object","properties":{}}}
 * @param displayLabel 运行时给前端展示的中文短句(如"正在查看你最近的训练片段…"),随 SSE tool 事件下发
 * @param readOnly     是否只读
 */
public record ToolSpec(
        String name,
        String description,
        String inputSchema,
        String displayLabel,
        boolean readOnly) {

    /** 无参只读工具的便捷构造 */
    public static ToolSpec readOnly(String name, String description, String displayLabel, String inputSchema) {
        return new ToolSpec(name, description, inputSchema, displayLabel, true);
    }
}
