package com.cnsportiot.edge.rules;

import com.cnsportiot.contracts.enums.FeedbackSeverity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一次规则命中(规则引擎的纯产物)。由 {@code RuleEngineListener} 翻成 WS 的 Cue/SafetyAlert 扇出,
 * 并转成 instant_feedback 条目上云。{@code eventId} 用于云端幂等去重。
 */
public record RuleHit(
        String eventId,
        UUID studentId,
        String displayName,
        String studentNo,
        String actionType,
        String checkpointId,
        String checkpointLabel,
        FeedbackSeverity severity,
        boolean safety,
        String cueText,
        String metric,
        double value,
        Double confidence,
        String sourceCamera,
        Double timestampMs,
        OffsetDateTime occurredAt) {
}
