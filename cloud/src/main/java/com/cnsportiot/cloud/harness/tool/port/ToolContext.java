package com.cnsportiot.cloud.harness.tool.port;

import com.cnsportiot.cloud.harness.llm.Tier;

import java.util.UUID;

/**
 * 一次工具调用的权威上下文
 *
 * 关键点:{@link #studentId} 是数据主体的唯一可信来源,来自 web 层已鉴权的 token,
 * {@code PreToolUseHook}(StudentScopeGuardHook)负责把它强制注入并拒绝任何越权入参。
 *
 * @param accountId 操作者账号(写审计 audit_log.account_id)
 * @param studentId 数据主体(审计 target_student_id;工具读取的唯一身份)
 * @param sessionId 触发调用的对话会话(可空,如调试端点直调)
 * @param tier      本次调用的档位(供工具决定拉取粒度,可空)
 */
public record ToolContext(UUID accountId, UUID studentId, UUID sessionId, Tier tier) {

    /** 调试端点/无会话场景:仅账号 + 主体 */
    public static ToolContext of(UUID accountId, UUID studentId) {
        return new ToolContext(accountId, studentId, null, Tier.FAST);
    }
}
