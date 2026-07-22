package com.cnsportiot.cloud.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 认证请求 DTO */
public final class AuthRequests {
    private AuthRequests() {}

    /** 2.1 登录 */
    public record LoginRequest(
            @NotBlank(message = "登录标识不能为空") @Size(max = 128) String identifier,
            @NotBlank(message = "密码不能为空") @Size(min = 6, max = 64) String password) {}

    /** 2.2 刷新令牌 */
    public record RefreshTokenRequest(
            @NotBlank(message = "刷新令牌不能为空") String refreshToken) {}

    /** 2.3 登出(携带待撤销的 refresh token) */
    public record LogoutRequest(
            @NotBlank String refreshToken) {}
}

