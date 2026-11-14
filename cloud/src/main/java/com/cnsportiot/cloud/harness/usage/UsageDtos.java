package com.cnsportiot.cloud.harness.usage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** token 用量对外结构:前端提示 + 运维审计共用。 */
public final class UsageDtos {

    private UsageDtos() {}

    /**
     * 当前用户的本周用量(前端提示用)。
     *
     * @param limit     本周上限;{@code null} = 不限
     * @param remaining 剩余;{@code null} = 不限
     * @param ratio     已用比例 0~1;不限时为 0
     * @param warning   已达告警比例(接近上限),前端可黄条提示
     * @param exceeded  已超限,下次提问会被拒(42911)
     * @param resetAt   配额清零时刻(下周一 00:00)
     */
    public record MyUsageResponse(
            UUID accountId,
            String role,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long calls,
            Long limit,
            Long remaining,
            double ratio,
            boolean warning,
            boolean exceeded,
            OffsetDateTime windowStart,
            OffsetDateTime resetAt) {}

    /** 运维总览:窗口内全局用量 + 活跃账号数 + 按来源拆分 + TopN。 */
    public record UsageOverviewResponse(
            int windowDays,
            OffsetDateTime since,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long calls,
            long activeAccounts,
            List<SourceUsage> bySource,
            List<AccountUsage> topAccounts) {}

    /** 按来源的用量拆分。 */
    public record SourceUsage(String source, long totalTokens, long calls) {}

    /** 按账号的用量(运维榜单/明细)。 */
    public record AccountUsage(
            UUID accountId,
            String username,
            String displayName,
            String role,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long calls,
            Long weeklyLimit,
            boolean exceeded) {}

    /** 按账号的用量列表。 */
    public record AccountUsageListResponse(
            int windowDays,
            OffsetDateTime since,
            List<AccountUsage> items) {}

    /** 按天的用量趋势点。 */
    public record DailyUsagePoint(
            OffsetDateTime day,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long calls) {}

    /** 用量趋势。 */
    public record UsageSeriesResponse(
            int windowDays,
            OffsetDateTime since,
            List<DailyUsagePoint> points) {}

    /** 单账号下钻:窗口聚合 + 本周配额状态 + 最近调用明细。 */
    public record AccountUsageDetailResponse(
            AccountUsage summary,
            MyUsageResponse currentWeek,
            List<UsageEntry> recent) {}

    /** 一条调用明细。 */
    public record UsageEntry(
            OffsetDateTime occurredAt,
            String source,
            String model,
            String tier,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean estimated,
            String finishReason,
            UUID sessionId) {}
}
