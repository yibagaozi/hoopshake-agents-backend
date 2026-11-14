package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.harness.tool.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hook 链装配点:Spring 按 {@code @Order} 注入有序的 Pre/Post 钩子列表,
 * {@link com.cnsportiot.cloud.harness.tool.ToolRunner} 在工具执行前后各跑一遍
 */
@Component
public class HookChain {

    private final List<PreToolUseHook> preHooks;
    private final List<PostToolUseHook> postHooks;

    public HookChain(List<PreToolUseHook> preHooks, List<PostToolUseHook> postHooks) {
        this.preHooks = preHooks;
        this.postHooks = postHooks;
    }

    /** 依次执行前置钩子;任一钩子抛出的异常向上传播(由 ToolRunner 处理) */
    public void runPre(ToolInvocation invocation) {
        for (PreToolUseHook h : preHooks) {
            h.before(invocation);
        }
    }

    /** 依次执行后置钩子,结果链式传递 */
    public Object runPost(ToolInvocation invocation, Object result) {
        Object out = result;
        for (PostToolUseHook h : postHooks) {
            out = h.after(invocation, out);
        }
        return out;
    }
}

