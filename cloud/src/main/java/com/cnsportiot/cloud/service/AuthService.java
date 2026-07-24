package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.AuthRequests.LoginRequest;
import com.cnsportiot.cloud.dto.response.AuthDtos.TokenResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.UserProfileResponse;
import com.cnsportiot.cloud.security.AuthUser;

/** 认证 */
public interface AuthService {

    /** 2.1 登录:校验标识+密码,签发双 token */
    TokenResponse login(LoginRequest request);

    /** 2.2 刷新:验证旧 refresh、轮换签发新双 token */
    TokenResponse refresh(String refreshToken);

    /** 2.3 登出:撤销该 refresh token,幂等 */
    void logout(String refreshToken);

    /** 2.4 当前用户 */
    UserProfileResponse me(AuthUser current);
}
