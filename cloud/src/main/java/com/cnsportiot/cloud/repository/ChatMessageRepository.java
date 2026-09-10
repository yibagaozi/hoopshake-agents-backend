package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** 会话消息,正序分页(3.3)。 */
    Page<ChatMessage> findByChatSessionIdOrderByCreatedAtAsc(UUID chatSessionId, Pageable pageable);

    /** 取最近若干轮(倒序取,调用方再翻转)组上下文窗口。 */
    List<ChatMessage> findByChatSessionIdOrderByCreatedAtDesc(UUID chatSessionId, Pageable pageable);

    /** 统计会话内某角色的消息数(协助触发:数学生提问轮次) */
    long countByChatSessionIdAndRole(UUID chatSessionId, MessageRole role);

    /**
     * 运维 Agent 表现近窗聚合:对
     * {@code chat_message.detail->'quality'} 近 N 小时汇总。仅学生对话落此信号
     * (用 {@code jsonb_exists(detail,'quality')} 过滤,避开 {@code ?} 与占位符冲突)。
     * 返回一行 7 列:answeredRuns, degradedRuns, ragHitRuns, avgAnswerChars,
     * toolOk, toolDeny, toolError。无数据时该行各列为 0/NULL(avg)
     */
    @Query(value = """
            SELECT
              count(*)                                                                       AS answered_runs,
              count(*) FILTER (WHERE (detail->'quality'->>'degraded') = 'true')              AS degraded_runs,
              count(*) FILTER (WHERE COALESCE((detail->'quality'->>'ragHitCount')::int, 0) > 0) AS rag_hit_runs,
              avg((detail->'quality'->>'answerChars')::numeric)                              AS avg_answer_chars,
              COALESCE(sum((detail->'quality'->>'toolOk')::int), 0)                          AS tool_ok,
              COALESCE(sum((detail->'quality'->>'toolDeny')::int), 0)                        AS tool_deny,
              COALESCE(sum((detail->'quality'->>'toolError')::int), 0)                       AS tool_error
            FROM chat_message
            WHERE role = 'ASSISTANT'
              AND jsonb_exists(detail, 'quality')
              AND created_at >= :since
            """, nativeQuery = true)
    List<Object[]> aggregateAgentQualitySince(@Param("since") OffsetDateTime since);
}
