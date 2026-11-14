package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.harness.tool.ToolInvocation;

/**
 * 工具执行后的钩子,对返回值做裁剪、字段白名单、脱敏,
 * 剥离算法内部字段(如原始逐帧坐标),只留结论层可下发内容。多个 Post 钩子按 {@code @Order} 升序链式处理
 */
public interface PostToolUseHook {

    /** 返回处理后的结果(可与入参 result 相同) */
    Object after(ToolInvocation invocation, Object result);
}
