package com.cnsportiot.cloud.harness.audit;

/**
 * 审计动作枚举值(写入 audit_log.action,见 §9.1)。
 * 与建档审计(STUDENT_CREATE 等,已有)共表。工具类审计(TOOL_INVOKE/DENY)在工具里程碑接入。
 */
public final class AuditAction {

    private AuditAction() {}

    // —— 知识库管理(本里程碑)——
    public static final String KNOWLEDGE_IMPORT = "KNOWLEDGE_IMPORT";
    public static final String KNOWLEDGE_DELETE = "KNOWLEDGE_DELETE";
    public static final String KNOWLEDGE_REINDEX = "KNOWLEDGE_REINDEX";

    // —— 对话 ——
    public static final String CHAT_ASK = "CHAT_ASK";

    // —— 工具调用(工具里程碑接入,此处预登记)——
    public static final String TOOL_INVOKE = "TOOL_INVOKE";
    public static final String TOOL_DENY = "TOOL_DENY";
}
