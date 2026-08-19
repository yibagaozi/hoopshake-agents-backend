package com.cnsportiot.cloud.harness.tool.port;

import java.util.Map;

/**
 * 一个可被 Agent 调用的工具。实现为普通 Spring {@code @Component},
 * 由 {@link ToolRegistry} 收集;不得 import 任何 Spring AI 类型
 *
 * <p>约定:
 * <ul>
 *   <li>{@link #execute} 里读取数据主体<b>只用</b> {@code ctx.studentId()},永不信任 {@code args} 里的身份字段;</li>
 *   <li>身份注入 / 越权拒绝 / 审计 / SSE 事件统一由 {@link ToolRunner} + Hook 链完成,工具本身只管取数;</li>
 *   <li>返回值需可被 Jackson 序列化(record / Map / List / 基本类型)——既回注给 LLM,也用于调试端点直出。</li>
 * </ul>
 */
public interface AgentTool {

    /** 工具规格(名字/描述/入参 schema/展示短句)。 */
    ToolSpec spec();

    /**
     * 执行取数。此处已在 Hook 链之内:{@code args} 是经 PreToolUseHook 清洗/校验后的入参,
     * 身份以 {@code ctx.studentId()} 为准。抛 {@code RuntimeException} 将被 {@link ToolRunner} 记为工具错误。
     */
    Object execute(Map<String, Object> args, ToolContext ctx);
}
