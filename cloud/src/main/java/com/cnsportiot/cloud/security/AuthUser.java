package com.cnsportiot.cloud.security;

import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.domain.enums.Role;

import java.util.UUID;

/** 鉴权上下文中的当前用户,由 access token 解析得到,注入 {@code @CurrentUser} 参数 */
public record AuthUser(
        UUID accountId,
        String username,
        Role role,
        UUID studentId,
        AccountStatus status) {

    public boolean isStudent() {
        return role == Role.STUDENT;
    }

    public boolean isActivated() {
        return status != AccountStatus.PENDING_ACTIVATION;
    }

    public boolean hasRole(Role... roles) {
        for (Role r : roles) {
            if (r == role) return true;
        }
        return false;
    }
}
