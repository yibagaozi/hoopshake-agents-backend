package com.cnsportiot.cloud.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 教师分析对话请求 DTO */
public final class TeacherChatRequests {
    private TeacherChatRequests() {}

    public record CreateTeacherChatSessionRequest(
            @Size(max = 64) String title) {}

    public record TeacherChatAskRequest(
            @NotBlank(message = "提问内容不能为空") @Size(max = 2000) String content) {}

    public record RenameTeacherChatSessionRequest(
            @NotBlank @Size(max = 64) String title) {}
}
