package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.AuthRequests.LoginRequest;
import com.cnsportiot.cloud.dto.request.AuthRequests.RegisterRequest;
import com.cnsportiot.cloud.dto.request.AuthRequests.ActivateRequest;
import com.cnsportiot.cloud.dto.response.AuthDtos.RegisterResponse;
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

    /** 2.5 教师注册 */
    RegisterResponse register(RegisterRequest request);

    /** 2.6 账号激活:PENDING_ACTIVATION → ACTIVE,设置手机号和新密码,返回新 token */
    TokenResponse activate(ActivateRequest request, AuthUser current);
}
