package com.cnsportiot.cloud.harness.audit;

/** 审计动作枚举值 */
public final class AuditAction {

    private AuditAction() {}

    // 知识库管理
    public static final String KNOWLEDGE_IMPORT = "KNOWLEDGE_IMPORT";
    public static final String KNOWLEDGE_DELETE = "KNOWLEDGE_DELETE";
    public static final String KNOWLEDGE_REINDEX = "KNOWLEDGE_REINDEX";

    // 对话
    public static final String CHAT_ASK = "CHAT_ASK";

    // 工具调用
    public static final String TOOL_INVOKE = "TOOL_INVOKE";
    public static final String TOOL_DENY = "TOOL_DENY";
}
