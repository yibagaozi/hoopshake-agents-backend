package com.cnsportiot.cloud.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 运维诊断对话请求 DTO(镜像教师侧,响应复用 ChatDtos) */
public final class OpsChatRequests {
    private OpsChatRequests() {}

    public record CreateOpsChatSessionRequest(
            @Size(max = 64) String title) {}

    public record OpsChatAskRequest(
            @NotBlank(message = "提问内容不能为空") @Size(max = 2000) String content) {}

    public record RenameOpsChatSessionRequest(
            @NotBlank @Size(max = 64) String title) {}
}
