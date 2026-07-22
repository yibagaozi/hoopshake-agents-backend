package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;

import java.util.UUID;

/** 认证相关响应 DTO */
public final class AuthDtos {
    private AuthDtos() {}

    /** 2.1 登录 / 2.2 刷新令牌 */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            long refreshExpiresInSeconds,
            UserSummary user) {}

    /** 登录返回的用户摘要 */
    public record UserSummary(
            UUID accountId,
            String username,
            Role role,
            AccountStatus status,
            UUID studentId,      // 仅 STUDENT
            String studentNo,    // 仅 STUDENT
            String displayName) {}

    /** 2.4 当前用户 */
    public record UserProfileResponse(
            UUID accountId,
            String username,
            String email,
            String phone,
            Role role,
            AccountStatus status,
            String staffNo,      // 教师/管理员
            UUID studentId,      // 仅 STUDENT
            String studentNo,
            String displayName) {}
}

