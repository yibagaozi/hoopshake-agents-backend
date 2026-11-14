package com.cnsportiot.cloud.harness.usage;

/**
 * token 用量来源。落在 {@code token_usage_record.source},用于审计时区分“用量花在哪条链路上”。
 * 用常量而非枚举:历史流水里的旧值不会因为枚举增删而读不出来。
 */
public final class UsageSource {

    private UsageSource() {}

    /** 学生端对话(SSE 流式)。 */
    public static final String STUDENT_CHAT = "STUDENT_CHAT";

    /** 教师端对话(SSE 流式)。 */
    public static final String TEACHER_CHAT = "TEACHER_CHAT";

    /** 运维助手对话。 */
    public static final String OPS_CHAT = "OPS_CHAT";

    /** 路由意图分类等 FAST 档短补全。 */
    public static final String ROUTER = "ROUTER";

    /** 其它/未归类。 */
    public static final String OTHER = "OTHER";
}
