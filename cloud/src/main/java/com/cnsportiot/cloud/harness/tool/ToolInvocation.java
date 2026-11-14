package com.cnsportiot.cloud.harness.tool;

import java.util.Map;

/**
 * 一次工具调用在 Hook 链中流转的可变载体。PreToolUseHook 可改写 {@link #args()}
 * PostToolUseHook 拿到结果做裁剪/脱敏
 */
public final class ToolInvocation {

    private final ToolSpec spec;
    private final Map<String, Object> args;
    private final ToolContext context;

    public ToolInvocation(ToolSpec spec, Map<String, Object> args, ToolContext context) {
        this.spec = spec;
        this.args = args;
        this.context = context;
    }

    public ToolSpec spec() {
        return spec;
    }

    /** 可变入参表 */
    public Map<String, Object> args() {
        return args;
    }

    public ToolContext context() {
        return context;
    }
}
