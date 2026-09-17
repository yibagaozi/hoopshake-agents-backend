package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireAuth;
import com.cnsportiot.cloud.harness.usage.TokenUsageService;
import com.cnsportiot.cloud.harness.usage.UsageDtos.MyUsageResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.contracts.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户的 AI 用量 */
@RestController
@RequestMapping("/api/me")
@RequireAuth
@RequiredArgsConstructor
public class MyAiUsageController {

    private final TokenUsageService tokenUsageService;

    /** 本周用量 + 配额余量 + 重置时刻 */
    @GetMapping("/ai-usage")
    public ApiResponse<MyUsageResponse> myUsage(@CurrentUser AuthUser me) {
        return ApiResponse.ok(tokenUsageService.myUsage(me.accountId(), me.role()));
    }
}
