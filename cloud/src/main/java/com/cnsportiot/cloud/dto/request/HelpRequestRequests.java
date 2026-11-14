package com.cnsportiot.cloud.dto.request;

import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 协助工单请求 DTO */
public final class HelpRequestRequests {
    private HelpRequestRequests() {}

    /** 学生发起协助(通常由 Agent 判定后展示的按钮触发,question 可用 Agent 归纳的疑问) */
    public record CreateHelpRequest(
            UUID sessionId,                                  // 触发的对话会话(可空)
            @NotBlank @Size(max = 2000) String question,     // 未解决的问题
            @Size(max = 2000) String contextNote) {}         // 学生补充说明(可空)

    /** 教师处理工单:置状态 + 可选答复 */
    public record HandleHelpRequest(
            @NotNull HelpRequestStatus status,
            @Size(max = 4000) String reply) {}
}
