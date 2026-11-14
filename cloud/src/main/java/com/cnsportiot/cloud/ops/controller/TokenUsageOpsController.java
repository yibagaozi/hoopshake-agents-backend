package com.cnsportiot.cloud.ops.controller;

import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.harness.usage.TokenUsageService;
import com.cnsportiot.cloud.harness.usage.UsageDtos.AccountUsageDetailResponse;
import com.cnsportiot.cloud.harness.usage.UsageDtos.AccountUsageListResponse;
import com.cnsportiot.cloud.harness.usage.UsageDtos.UsageOverviewResponse;
import com.cnsportiot.cloud.harness.usage.UsageDtos.UsageSeriesResponse;
import com.cnsportiot.contracts.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * token 用量审计(运维台)。ADMIN 角色
 * 与 {@link OpsController} 同一套口径:首屏拉 {@link #overview},需要下钻再打子端点。
 * 窗口参数 {@code windowDays} 是"最近 N 天"(滚动窗),与周配额的自然周窗口是两回事:
 * 前者看趋势,后者判是否超限——单账号的配额状态在 {@link #account} 的 {@code currentWeek} 里看
 */
@RestController
@RequestMapping("/api/ops/token-usage")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class TokenUsageOpsController {

    private final TokenUsageService tokenUsageService;

    /** 总览:窗口内总用量、活跃账号数、按来源拆分、TopN 账号 */
    @GetMapping("/overview")
    public ApiResponse<UsageOverviewResponse> overview(
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays,
            @RequestParam(name = "topN", defaultValue = "10") int topN) {
        return ApiResponse.ok(tokenUsageService.overview(windowDays, topN));
    }

    /** 按账号的用量榜(默认取前 50) */
    @GetMapping("/accounts")
    public ApiResponse<AccountUsageListResponse> accounts(
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(tokenUsageService.accounts(windowDays, limit));
    }

    /** 按天趋势(画折线用) */
    @GetMapping("/series")
    public ApiResponse<UsageSeriesResponse> series(
            @RequestParam(name = "windowDays", defaultValue = "30") int windowDays) {
        return ApiResponse.ok(tokenUsageService.series(windowDays));
    }

    /** 单账号下钻:窗口聚合 + 本周配额状态 + 最近调用明细 */
    @GetMapping("/accounts/{accountId}")
    public ApiResponse<AccountUsageDetailResponse> account(
            @PathVariable UUID accountId,
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays,
            @RequestParam(name = "recentLimit", defaultValue = "50") int recentLimit) {
        return ApiResponse.ok(tokenUsageService.accountDetail(accountId, windowDays, recentLimit));
    }
}
