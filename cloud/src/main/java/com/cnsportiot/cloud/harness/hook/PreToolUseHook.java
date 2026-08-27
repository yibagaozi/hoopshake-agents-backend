package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.harness.tool.ToolInvocation;

/**
 * 工具执行前的钩子,可改写入参、注入权威身份、做前置校验
 * 越权时抛 {@code BusinessException(DATA_SCOPE_DENIED)};{@link com.cnsportiot.cloud.harness.tool.ToolRunner}
 * 捕获后记 {@code TOOL_DENY} 审计并收敛为 DENIED 结果
 * 多个 Pre 钩子按 {@code @Order} 升序执行
 */
public interface PreToolUseHook {

    void before(ToolInvocation invocation);
}
