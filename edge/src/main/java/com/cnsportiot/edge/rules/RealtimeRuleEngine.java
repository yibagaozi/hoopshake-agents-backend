package com.cnsportiot.edge.rules;

import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.edge.realtime.WsEvents.ActionSample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 实时 2D 规则引擎(**纯逻辑,不依赖 Spring/网络,可脱机单测**)。
 *
 * <p>输入一条 {@link ActionSample}(某学生某相位的 2D 可算量),输出 0..N 条 {@link RuleHit}:
 * <ol>
 *   <li>按(动作类型,相位)取词表条目;</li>
 *   <li>取 {@code measured[metric]};缺该量→跳过(不算失败);</li>
 *   <li>置信不足→跳过;</li>
 *   <li>低于/高于达标带→取对应话术 + 严重度;带内且配了正反馈→POSITIVE;</li>
 *   <li>同(学生,检查点)冷却期内→抑制(防刷屏)。</li>
 * </ol>
 * 时间经 {@link Clock} 注入,冷却与 occurredAt 可在测试中确定性推进。
 */
@Component
public class RealtimeRuleEngine {

    private enum Band { LOW, IN, HIGH }

    private final CheckpointCatalog catalog;
    private final CheckpointProperties props;
    private final Clock clock;

    /** (studentId|checkpointId) → 上次推送时刻(ms)。 */
    private final ConcurrentMap<String, Long> lastEmit = new ConcurrentHashMap<>();

    @Autowired
    public RealtimeRuleEngine(CheckpointCatalog catalog, CheckpointProperties props) {
        this(catalog, props, Clock.systemUTC());
    }

    RealtimeRuleEngine(CheckpointCatalog catalog, CheckpointProperties props, Clock clock) {
        this.catalog = catalog;
        this.props = props;
        this.clock = clock;
    }

    public List<RuleHit> evaluate(ActionSample s) {
        if (s == null || s.studentId() == null || s.actionType() == null || s.phase() == null) {
            return List.of();
        }
        long nowMs = clock.millis();
        OffsetDateTime occurredAt = s.occurredAt() != null ? s.occurredAt() : OffsetDateTime.now(clock);
        List<RuleHit> hits = new ArrayList<>();

        for (CheckpointRule r : catalog.match(s.actionType(), s.phase())) {
            Double val = numeric(s.measured(), r.getMetric());
            if (val == null) {
                continue;   // 本帧没算出该量 → 跳过
            }
            double minConf = r.getMinConfidence() != null ? r.getMinConfidence() : props.getMinConfidence();
            if (s.confidence() != null && s.confidence() < minConf) {
                continue;   // 置信不足不推
            }

            Band band = band(val, r.getMin(), r.getMax());
            String cue;
            FeedbackSeverity sev;
            if (band == Band.IN) {
                if (isBlank(r.getCueOk())) {
                    continue;   // 达标且未配正反馈 → 不推
                }
                cue = r.getCueOk();
                sev = FeedbackSeverity.POSITIVE;
            } else {
                cue = band == Band.LOW ? r.getCueLow() : r.getCueHigh();
                if (isBlank(cue)) {
                    continue;   // 越界但没配话术 → 不推
                }
                sev = outOfBandSeverity(r.getSeverity());
            }

            // 去抖:冷却期内同(学生,检查点)不重复
            long cooldown = r.getCooldownMs() != null ? r.getCooldownMs() : props.getDefaultCooldownMs();
            String key = s.studentId() + "|" + r.getId();
            Long last = lastEmit.get(key);
            if (last != null && nowMs - last < cooldown) {
                continue;
            }
            lastEmit.put(key, nowMs);

            String eventId = s.studentId() + ":" + r.getId() + ":"
                    + (s.timestampMs() != null ? Math.round(s.timestampMs()) : nowMs);
            hits.add(new RuleHit(
                    eventId, s.studentId(), s.displayName(), s.studentNo(),
                    s.actionType(), r.getId(), r.getCheckpointLabel(), sev, r.isSafety(),
                    cue, r.getMetric(), val, s.confidence(), s.sourceCamera(), s.timestampMs(), occurredAt));
        }
        return hits;
    }

    /** 清空去抖状态(测试/会话切换用)。 */
    public void reset() {
        lastEmit.clear();
    }

    private static Band band(double val, Double min, Double max) {
        if (min != null && val < min) {
            return Band.LOW;
        }
        if (max != null && val > max) {
            return Band.HIGH;
        }
        return Band.IN;
    }

    /** 越界严重度:仅 MINOR/MAJOR 有意义;缺省或非法(含误配 POSITIVE)→ MAJOR。 */
    private static FeedbackSeverity outOfBandSeverity(String s) {
        if (s != null) {
            try {
                FeedbackSeverity sev = FeedbackSeverity.valueOf(s.trim().toUpperCase());
                if (sev == FeedbackSeverity.MINOR || sev == FeedbackSeverity.MAJOR) {
                    return sev;
                }
            } catch (IllegalArgumentException ignore) {
                // 落回默认
            }
        }
        return FeedbackSeverity.MAJOR;
    }

    private static Double numeric(Map<String, Object> measured, String key) {
        if (measured == null || key == null) {
            return null;
        }
        Object v = measured.get(key);
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (v != null && !String.valueOf(v).isBlank()) {
            try {
                double d = Double.parseDouble(String.valueOf(v).trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
