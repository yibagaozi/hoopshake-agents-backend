package com.cnsportiot.cloud.controller;


import com.cnsportiot.cloud.service.AuthService;
import com.cnsportiot.cloud.common.ApiResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.TokenResponse;
import com.cnsportiot.cloud.dto.response.AuthDtos.UserProfileResponse;
import com.cnsportiot.cloud.dto.request.AuthRequests.LoginRequest;
import com.cnsportiot.cloud.dto.request.AuthRequests.LogoutRequest;
import com.cnsportiot.cloud.dto.request.AuthRequests.RefreshTokenRequest;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireAuth;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 认证入口 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @RequireAuth
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    @RequireAuth
    public ApiResponse<UserProfileResponse> me(@CurrentUser AuthUser current) {
        return ApiResponse.ok(authService.me(current));
    }
}
