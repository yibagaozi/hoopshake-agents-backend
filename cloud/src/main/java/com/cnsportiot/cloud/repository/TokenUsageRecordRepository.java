package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.TokenUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * token 用量流水仓储。配额判定只需 {@link #sumTotalSince}(单列求和,走
 * {@code idx_tur_account_time} 索引);审计看板用投影聚合,避免把整表流水拉进内存。
 */
public interface TokenUsageRecordRepository extends JpaRepository<TokenUsageRecord, UUID> {

    /** 某账号自 {@code since} 起的合计 token(周配额判定用)。无记录返回 0 而非 null。 */
    @Query("""
            SELECT COALESCE(SUM(r.totalTokens), 0) FROM TokenUsageRecord r
            WHERE r.accountId = :accountId AND r.occurredAt >= :since
            """)
    long sumTotalSince(@Param("accountId") UUID accountId, @Param("since") OffsetDateTime since);

    /** 某账号自 {@code since} 起的输入/输出/次数明细(前端提示 + 个人审计)。 */
    @Query("""
            SELECT COALESCE(SUM(r.promptTokens), 0) AS promptTokens,
                   COALESCE(SUM(r.completionTokens), 0) AS completionTokens,
                   COALESCE(SUM(r.totalTokens), 0) AS totalTokens,
                   COUNT(r) AS calls
            FROM TokenUsageRecord r
            WHERE r.accountId = :accountId AND r.occurredAt >= :since
            """)
    UsageSum sumDetailSince(@Param("accountId") UUID accountId, @Param("since") OffsetDateTime since);

    /** 全局自 {@code since} 起的合计(ops 概览)。 */
    @Query("""
            SELECT COALESCE(SUM(r.promptTokens), 0) AS promptTokens,
                   COALESCE(SUM(r.completionTokens), 0) AS completionTokens,
                   COALESCE(SUM(r.totalTokens), 0) AS totalTokens,
                   COUNT(r) AS calls
            FROM TokenUsageRecord r
            WHERE r.occurredAt >= :since
            """)
    UsageSum sumAllSince(@Param("since") OffsetDateTime since);

    /** 自 {@code since} 起活跃(有用量)的账号数。 */
    @Query("""
            SELECT COUNT(DISTINCT r.accountId) FROM TokenUsageRecord r
            WHERE r.occurredAt >= :since
            """)
    long countActiveAccountsSince(@Param("since") OffsetDateTime since);

    /** 按账号聚合的用量榜(ops 审计;调用方按 Pageable 截断取 TopN)。 */
    @Query("""
            SELECT r.accountId AS accountId,
                   COALESCE(SUM(r.promptTokens), 0) AS promptTokens,
                   COALESCE(SUM(r.completionTokens), 0) AS completionTokens,
                   COALESCE(SUM(r.totalTokens), 0) AS totalTokens,
                   COUNT(r) AS calls
            FROM TokenUsageRecord r
            WHERE r.occurredAt >= :since
            GROUP BY r.accountId
            ORDER BY SUM(r.totalTokens) DESC
            """)
    List<AccountUsageSum> aggregateByAccountSince(@Param("since") OffsetDateTime since,
                                                  org.springframework.data.domain.Pageable pageable);

    /** 按来源(STUDENT_CHAT/TEACHER_CHAT/…)聚合,看用量花在哪。 */
    @Query("""
            SELECT r.source AS source,
                   COALESCE(SUM(r.totalTokens), 0) AS totalTokens,
                   COUNT(r) AS calls
            FROM TokenUsageRecord r
            WHERE r.occurredAt >= :since
            GROUP BY r.source
            ORDER BY SUM(r.totalTokens) DESC
            """)
    List<SourceUsageSum> aggregateBySourceSince(@Param("since") OffsetDateTime since);

    /**
     * 按天的用量趋势。date_trunc 是 PostgreSQL 方言,故用原生 SQL;
     * 返回 [day(timestamptz), prompt, completion, total, calls]。
     */
    @Query(value = """
            SELECT date_trunc('day', occurred_at) AS day,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COUNT(*) AS calls
            FROM token_usage_record
            WHERE occurred_at >= :since
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> dailySeriesSince(@Param("since") OffsetDateTime since);

    /** 单账号最近若干条明细(ops 下钻)。 */
    List<TokenUsageRecord> findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            UUID accountId, OffsetDateTime since, org.springframework.data.domain.Pageable pageable);

    /** 输入/输出/合计/次数投影。 */
    interface UsageSum {
        long getPromptTokens();
        long getCompletionTokens();
        long getTotalTokens();
        long getCalls();
    }

    /** 按账号聚合的投影。 */
    interface AccountUsageSum extends UsageSum {
        UUID getAccountId();
    }

    /** 按来源聚合的投影。 */
    interface SourceUsageSum {
        String getSource();
        long getTotalTokens();
        long getCalls();
    }
}
