package com.cnsportiot.cloud.harness.usage;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.TokenUsageRecord;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.TokenUsageRecordRepository;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单用户 token 用量的记账、周配额判定与审计聚合。
 *
 * <p><b>记账</b>与 {@link com.cnsportiot.cloud.harness.audit.AuditService} 同样的纪律:独立事务、
 * 失败只记日志不阻断对话——用量写不进去是运维问题,不该让用户的提问失败。
 *
 * <p><b>配额</b>按自然周(周一 00:00,时区取 {@code hoopshake.agent.quota.zone})聚合流水判定,
 * 不存"已用量"这种可变计数器:流水是唯一事实来源,到点自动换窗,免定时清零任务。
 * 判定发生在调用 LLM 之前;超限抛 {@code TOKEN_QUOTA_EXCEEDED(42911)}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageRecordRepository usageRepo;
    private final AccountRepository accountRepository;
    private final AgentProperties props;

    // ---- 配额 ----

    /**
     * 提问前闸门:本周用量已达上限则拒绝。
     * 配额关闭、或该角色不限额时直接放行。
     *
     * @throws BusinessException TOKEN_QUOTA_EXCEEDED(42911) 本周额度用尽
     */
    public void ensureWithinWeeklyQuota(UUID accountId, Role role) {
        AgentProperties.Quota q = props.getQuota();
        if (!q.isEnabled() || accountId == null) {
            return;
        }
        Long limit = limitFor(role);
        if (limit == null) {
            return;   // 该角色不限额
        }
        long used = usageRepo.sumTotalSince(accountId, weekStart());
        if (used >= limit) {
            throw new BusinessException(ErrorCode.TOKEN_QUOTA_EXCEEDED,
                    "本周 AI 用量已达上限(" + used + "/" + limit + " tokens),将于 "
                            + resetAt() + " 重置。");
        }
    }

    /** 某角色的周上限;{@code null} = 不限。 */
    public Long limitFor(Role role) {
        AgentProperties.Quota q = props.getQuota();
        long limit = switch (role == null ? Role.STUDENT : role) {
            case STUDENT -> q.getWeeklyTokensStudent();
            case TEACHER -> q.getWeeklyTokensTeacher();
            case ADMIN -> q.getWeeklyTokensAdmin();
        };
        return limit > 0 ? limit : null;
    }

    // ---- 记账 ----

    /**
     * 记一条用量流水。独立事务,失败不上抛。
     *
     * @param usage 网关回传的用量(可空:空则不记)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID accountId, Role role, String source, LlmGateway.Usage usage,
                       Tier tier, UUID sessionId, String finishReason) {
        if (accountId == null || usage == null) {
            return;
        }
        try {
            usageRepo.save(TokenUsageRecord.builder()
                    .accountId(accountId)
                    .accountRole(role == null ? null : role.name())
                    .source(source == null ? UsageSource.OTHER : source)
                    .model(usage.model())
                    .tier(tier == null ? null : tier.name())
                    .promptTokens(Math.max(0, usage.promptTokens()))
                    .completionTokens(Math.max(0, usage.completionTokens()))
                    .totalTokens(Math.max(0, usage.totalTokens()))
                    .estimated(usage.estimated())
                    .sessionId(sessionId)
                    .finishReason(finishReason)
                    .occurredAt(OffsetDateTime.now())
                    .build());
        } catch (RuntimeException e) {
            // 记账失败不能阻断对话
            log.error("token 用量写入失败 account={} source={} tokens={}",
                    accountId, source, usage.totalTokens(), e);
        }
    }

    // ---- 前端提示 ----

    /** 当前账号的本周用量快照(前端顶栏/提示条用)。 */
    @Transactional(readOnly = true)
    public UsageDtos.MyUsageResponse myUsage(UUID accountId, Role role) {
        OffsetDateTime since = weekStart();
        var sum = usageRepo.sumDetailSince(accountId, since);
        long total = sum == null ? 0 : sum.getTotalTokens();
        Long limit = limitFor(role);
        Long remaining = limit == null ? null : Math.max(0, limit - total);
        double ratio = (limit == null || limit == 0) ? 0 : Math.min(1.0, (double) total / limit);
        return new UsageDtos.MyUsageResponse(
                accountId,
                role == null ? null : role.name(),
                sum == null ? 0 : sum.getPromptTokens(),
                sum == null ? 0 : sum.getCompletionTokens(),
                total,
                sum == null ? 0 : sum.getCalls(),
                limit,
                remaining,
                ratio,
                limit != null && ratio >= props.getQuota().getWarnRatio(),
                limit != null && total >= limit,
                since,
                resetAt());
    }

    // ---- 运维审计 ----

    /** 窗口内全局总览 + 按来源拆分 + TopN 账号。 */
    @Transactional(readOnly = true)
    public UsageDtos.UsageOverviewResponse overview(int windowDays, int topN) {
        OffsetDateTime since = sinceDays(windowDays);
        var all = usageRepo.sumAllSince(since);
        List<UsageDtos.SourceUsage> bySource = usageRepo.aggregateBySourceSince(since).stream()
                .map(s -> new UsageDtos.SourceUsage(s.getSource(), s.getTotalTokens(), s.getCalls()))
                .toList();
        return new UsageDtos.UsageOverviewResponse(
                windowDays, since,
                all == null ? 0 : all.getPromptTokens(),
                all == null ? 0 : all.getCompletionTokens(),
                all == null ? 0 : all.getTotalTokens(),
                all == null ? 0 : all.getCalls(),
                usageRepo.countActiveAccountsSince(since),
                bySource,
                topAccounts(since, topN));
    }

    /** 窗口内按账号的用量榜。 */
    @Transactional(readOnly = true)
    public UsageDtos.AccountUsageListResponse accounts(int windowDays, int limit) {
        OffsetDateTime since = sinceDays(windowDays);
        return new UsageDtos.AccountUsageListResponse(windowDays, since, topAccounts(since, limit));
    }

    /** 按天趋势。 */
    @Transactional(readOnly = true)
    public UsageDtos.UsageSeriesResponse series(int windowDays) {
        OffsetDateTime since = sinceDays(windowDays);
        List<UsageDtos.DailyUsagePoint> points = new ArrayList<>();
        for (Object[] row : usageRepo.dailySeriesSince(since)) {
            points.add(new UsageDtos.DailyUsagePoint(
                    toOffset(row[0]),
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue()));
        }
        return new UsageDtos.UsageSeriesResponse(windowDays, since, points);
    }

    /** 单账号下钻:窗口聚合 + 本周配额状态 + 最近 N 条明细。 */
    @Transactional(readOnly = true)
    public UsageDtos.AccountUsageDetailResponse accountDetail(UUID accountId, int windowDays, int recentLimit) {
        OffsetDateTime since = sinceDays(windowDays);
        Account account = accountRepository.findById(accountId).orElse(null);
        Role role = account == null ? null : account.getRole();
        var sum = usageRepo.sumDetailSince(accountId, since);
        Long weeklyLimit = limitFor(role);
        long weekUsed = usageRepo.sumTotalSince(accountId, weekStart());

        UsageDtos.AccountUsage summary = new UsageDtos.AccountUsage(
                accountId,
                account == null ? null : account.getUsername(),
                account == null ? null : account.getDisplayName(),
                role == null ? null : role.name(),
                sum == null ? 0 : sum.getPromptTokens(),
                sum == null ? 0 : sum.getCompletionTokens(),
                sum == null ? 0 : sum.getTotalTokens(),
                sum == null ? 0 : sum.getCalls(),
                weeklyLimit,
                weeklyLimit != null && weekUsed >= weeklyLimit);

        List<UsageDtos.UsageEntry> recent = usageRepo
                .findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        accountId, since, PageRequest.of(0, Math.max(1, recentLimit)))
                .stream()
                .map(r -> new UsageDtos.UsageEntry(
                        r.getOccurredAt(), r.getSource(), r.getModel(), r.getTier(),
                        r.getPromptTokens(), r.getCompletionTokens(), r.getTotalTokens(),
                        r.isEstimated(), r.getFinishReason(), r.getSessionId()))
                .toList();

        return new UsageDtos.AccountUsageDetailResponse(summary, myUsage(accountId, role), recent);
    }

    // ---- 内部 ----

    private List<UsageDtos.AccountUsage> topAccounts(OffsetDateTime since, int limit) {
        var rows = usageRepo.aggregateByAccountSince(since, PageRequest.of(0, Math.max(1, limit)));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 批量补账号信息,避免逐行查库
        Map<UUID, Account> accounts = new HashMap<>();
        accountRepository.findAllById(rows.stream().map(r -> r.getAccountId()).toList())
                .forEach(a -> accounts.put(a.getId(), a));

        OffsetDateTime weekStart = weekStart();
        List<UsageDtos.AccountUsage> out = new ArrayList<>(rows.size());
        for (var r : rows) {
            Account a = accounts.get(r.getAccountId());
            Role role = a == null ? null : a.getRole();
            Long weeklyLimit = limitFor(role);
            boolean exceeded = weeklyLimit != null
                    && usageRepo.sumTotalSince(r.getAccountId(), weekStart) >= weeklyLimit;
            out.add(new UsageDtos.AccountUsage(
                    r.getAccountId(),
                    a == null ? null : a.getUsername(),
                    a == null ? null : a.getDisplayName(),
                    role == null ? null : role.name(),
                    r.getPromptTokens(), r.getCompletionTokens(), r.getTotalTokens(), r.getCalls(),
                    weeklyLimit, exceeded));
        }
        return out;
    }

    /** 本自然周起点(周一 00:00,配置时区)。 */
    private OffsetDateTime weekStart() {
        ZoneId zone = zone();
        return OffsetDateTime.now(zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(zone).toOffsetDateTime();
    }

    /** 配额清零时刻(下周一 00:00)。 */
    private OffsetDateTime resetAt() {
        return weekStart().plusWeeks(1);
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(props.getQuota().getZone());
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private static OffsetDateTime sinceDays(int days) {
        return OffsetDateTime.now().minusDays(Math.max(1, days));
    }

    /** 原生查询的 day 列可能是 Timestamp / OffsetDateTime,统一成 OffsetDateTime。 */
    private static OffsetDateTime toOffset(Object v) {
        if (v instanceof OffsetDateTime o) {
            return o;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (v instanceof java.time.Instant i) {
            return i.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        return null;
    }
}
