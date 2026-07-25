package com.cnsportiot.cloud.dto.request;

import jakarta.validation.constraints.Email;
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

    /** 2.5 教师注册 */
    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
            @NotBlank(message = "密码不能为空") @Size(min = 6, max = 64) String password,
            @NotBlank(message = "姓名不能为空") @Size(max = 64) String displayName,
            @NotBlank(message = "工号不能为空") @Size(max = 32) String staffNo,
            @Email(message = "邮箱格式不正确") @Size(max = 128) String email,
            @Size(max = 32) String phone,
            /** 邀请码;服务端配置了 invite-code 时必填且需匹配 */
            String inviteCode) {}
}

