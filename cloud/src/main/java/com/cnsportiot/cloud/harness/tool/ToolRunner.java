package com.cnsportiot.cloud.harness.tool;

import com.cnsportiot.cloud.harness.audit.AuditAction;
import com.cnsportiot.cloud.harness.audit.AuditService;
import com.cnsportiot.cloud.harness.hook.HookChain;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用的统一执行器: 解析 → PreHook(闸门) → 执行 → PostHook(裁剪) → 审计 → 事件
 * 对话链路(Spring AI 适配器)与调试端点走同一条路径,保证 Hook / 审计 / 语义一致
 * 越权/失败收敛为 {@link ToolResult}(DENIED/ERROR),
 * 审计走 {@link AuditService} 的 {@code REQUIRES_NEW} 独立事务
 */
@Slf4j
@Component
public class ToolRunner {

    private final ToolRegistry registry;
    private final HookChain hooks;
    private final AuditService audit;

    public ToolRunner(ToolRegistry registry, HookChain hooks, AuditService audit) {
        this.registry = registry;
        this.hooks = hooks;
        this.audit = audit;
    }

    public ToolResult run(String toolName, Map<String, Object> rawArgs, ToolContext ctx, ToolEventListener listener) {
        ToolEventListener l = listener == null ? ToolEventListener.NOOP : listener;

        AgentTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            log.warn("调用未知工具: {}", toolName);
            l.onEvent(toolName, ToolEventListener.ERROR, null);
            return ToolResult.error(toolName, null, "工具不存在: " + toolName);
        }

        ToolSpec spec = tool.spec();
        String label = spec.displayLabel();
        Map<String, Object> args = new LinkedHashMap<>();
        if (rawArgs != null) {
            args.putAll(rawArgs);
        }
        ToolInvocation inv = new ToolInvocation(spec, args, ctx);

        l.onEvent(toolName, ToolEventListener.RUNNING, label);
        long t0 = System.nanoTime();
        try {
            hooks.runPre(inv);
            Object data = tool.execute(inv.args(), ctx);
            data = hooks.runPost(inv, data);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            audit.record(ctx.accountId(), AuditAction.TOOL_INVOKE, ctx.studentId(),
                    detail(spec.name(), inv.args(), ctx, "ok", ms, null));
            l.onEvent(toolName, ToolEventListener.OK, label);
            return ToolResult.ok(toolName, data, label);

        } catch (BusinessException be) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (be.errorCode() == ErrorCode.DATA_SCOPE_DENIED) {
                audit.record(ctx.accountId(), AuditAction.TOOL_DENY, ctx.studentId(),
                        detail(spec.name(), inv.args(), ctx, "denied", ms, be.getMessage()));
                l.onEvent(toolName, ToolEventListener.DENIED, label);
                return ToolResult.denied(toolName, label, be.getMessage());
            }
            audit.record(ctx.accountId(), AuditAction.TOOL_INVOKE, ctx.studentId(),
                    detail(spec.name(), inv.args(), ctx, "error", ms, be.getMessage()));
            l.onEvent(toolName, ToolEventListener.ERROR, label);
            return ToolResult.error(toolName, label, be.getMessage());

        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.error("工具执行异常 tool={} student={}", toolName, ctx.studentId(), e);
            audit.record(ctx.accountId(), AuditAction.TOOL_INVOKE, ctx.studentId(),
                    detail(spec.name(), inv.args(), ctx, "error", ms, e.toString()));
            l.onEvent(toolName, ToolEventListener.ERROR, label);
            return ToolResult.error(toolName, label, "工具执行失败");
        }
    }

    /** 审计 detail(jsonb)。入参已经 Hook 剥除身份字段,直接落用于复盘;值转字符串防超大对象 */
    private Map<String, Object> detail(String tool, Map<String, Object> args, ToolContext ctx,
                                       String result, long ms, String message) {
        Map<String, Object> d = new HashMap<>();
        d.put("tool", tool);
        d.put("result", result);
        d.put("elapsedMs", ms);
        if (ctx.sessionId() != null) {
            d.put("chatSessionId", ctx.sessionId().toString());
        }
        Map<String, Object> a = new LinkedHashMap<>();
        if (args != null) {
            args.forEach((k, v) -> a.put(k, v == null ? null : String.valueOf(v)));
        }
        d.put("args", a);
        if (message != null) {
            d.put("message", message);
        }
        return d;
    }
}

