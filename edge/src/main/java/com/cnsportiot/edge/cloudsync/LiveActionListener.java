package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.realtime.EdgeEvent;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import com.cnsportiot.edge.rules.CheckpointProperties;
import com.cnsportiot.edge.rules.FeedbackForwarder;
import com.cnsportiot.edge.rules.RealtimeRuleEngine;
import com.cnsportiot.edge.rules.RuleHit;
import com.cnsportiot.edge.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 算法 v2.2.0 直播动作事件接线:订阅 {@link WsEventType#ACTION_FINALIZED}(由 {@link com.cnsportiot.edge.cv.AlgoLiveClient}
 * 从算法 WS 收并解析),一条投篮动作:
 * <ol>
 *   <li>身份绑定:{@code global_id}/{@code stu_XX} → 学号 → studentId(经 {@link IdentityBindingStore});</li>
 *   <li>大屏:发 {@link WsEventType#ACTION_FOCUS}(谁、什么动作、命中);</li>
 *   <li>提示:把每相位的 angles 折成 {@code measured} 喂 {@link RealtimeRuleEngine} → cue/safetyAlert + instant_feedback;</li>
 *   <li>落库:单条 action_clip 推云(score.release_angles 供云端派生标准度),供 agent 分析。</li>
 * </ol>
 * 未标定时算法 angles 为 null → 无提示(只动作级);身份未绑定则按配置只上屏不落库。
 */
@Component
public class LiveActionListener {

    private static final Logger log = LoggerFactory.getLogger(LiveActionListener.class);

    private final EdgeEventPublisher publisher;
    private final IdentityBindingStore bindings;
    private final SessionService sessionService;
    private final RealtimeRuleEngine engine;
    private final CheckpointProperties ruleProps;
    private final FeedbackForwarder forwarder;
    private final CloudIngestClient cloud;
    private final TaskExecutor cloudIoExecutor;
    private final EdgeProperties props;
    private final ObjectMapper objectMapper;

    private static final long ENROLL_PROMPT_COOLDOWN_MS = 15_000;

    private final ConcurrentMap<String, AtomicInteger> clipCounters = new ConcurrentHashMap<>();
    /** 待绑定提示去抖:同一身份 15s 内只提醒一次,避免每投一次刷一条。 */
    private final ConcurrentMap<String, Long> lastEnrollPrompt = new ConcurrentHashMap<>();

    public LiveActionListener(EdgeEventPublisher publisher, IdentityBindingStore bindings,
                              SessionService sessionService, RealtimeRuleEngine engine,
                              CheckpointProperties ruleProps, FeedbackForwarder forwarder,
                              CloudIngestClient cloud,
                              @Qualifier("cloudIoExecutor") TaskExecutor cloudIoExecutor,
                              EdgeProperties props, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.bindings = bindings;
        this.sessionService = sessionService;
        this.engine = engine;
        this.ruleProps = ruleProps;
        this.forwarder = forwarder;
        this.cloud = cloud;
        this.cloudIoExecutor = cloudIoExecutor;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onEdgeEvent(EdgeEvent event) {
        if (event.type() != WsEventType.ACTION_FINALIZED) {
            return;
        }
        WsEvents.ActionFinalized a;
        try {
            // payload 是算法原生结构(snake_case),由 /internal/cv/stream 透传过来;显式解析,不靠字段名自动映射
            JsonNode n = event.payload() instanceof JsonNode jn ? jn : objectMapper.valueToTree(event.payload());
            a = parseActionFinalized(n);
        } catch (RuntimeException e) {
            log.warn("action_finalized 解析失败,忽略: {}", e.getMessage());
            return;
        }

        Optional<IdentityBindingStore.Binding> bound = bindings.resolve(a.globalId(), a.studentLocalId());
        String studentNo = bound.map(IdentityBindingStore.Binding::studentNo).orElse(null);
        UUID studentId = bound.map(IdentityBindingStore.Binding::studentId)
                .map(LiveActionListener::parseUuid).orElse(null);
        String displayName = bound.map(IdentityBindingStore.Binding::displayName)
                .filter(s -> s != null && !s.isBlank()).orElse(a.studentLocalId());
        OffsetDateTime now = OffsetDateTime.now();

        // 1) 大屏动作级提示
        publisher.publish(WsEventType.ACTION_FOCUS, toFocus(a, studentId, studentNo, displayName));

        UUID sessionId = currentSessionId();

        // 2) angles → cue(需规则开关 + 有身份 + 有角度)
        if (ruleProps.isEnabled() && (studentNo != null || studentId != null)
                && a.actionType() != null && a.angles() != null && !a.angles().isEmpty()) {
            emitCues(a, sessionId, studentId, studentNo, displayName, now);
        }

        // 3) 落库(需会话 + 身份;未绑定按配置决定是否“未归属”落)
        boolean hasIdentity = studentNo != null || studentId != null;
        if (!hasIdentity) {
            // 未绑定身份在投篮 → 提醒操作台/注册页“有新面孔,请输学号”(按身份去抖)
            promptEnrollIfNew(a);
        }
        if (sessionId == null || (!hasIdentity && !props.getLive().isPersistUnbound())) {
            log.debug("直播动作只上屏不落库 session={} 身份={}", sessionId, hasIdentity);
            return;
        }
        String idKey = studentId != null ? studentId.toString() : (studentNo != null ? studentNo : a.studentLocalId());
        int clipIndex = nextClipIndex(sessionId, idKey);
        Map<String, Object> clip = toClip(a, studentId, studentNo, clipIndex);
        cloudIoExecutor.execute(() -> {
            try {
                cloud.pushActionClips(sessionId, List.of(clip));
            } catch (RuntimeException e) {
                log.warn("直播动作落库失败 session={} id={} clip={}: {}", sessionId, idKey, clipIndex, e.getMessage());
            }
        });
    }

    /** 每相位取落在相位内、最靠中点的一帧 angles,折成 measured 喂规则引擎。 */
    private void emitCues(WsEvents.ActionFinalized a, UUID sessionId, UUID studentId,
                          String studentNo, String displayName, OffsetDateTime now) {
        for (Map<String, Object> phase : a.phases() == null ? List.<Map<String, Object>>of() : a.phases()) {
            String phaseName = str(phase, "name");
            Map<String, Object> row = representativeRow(a.angles(), phase);
            if (phaseName == null || row == null) {
                continue;
            }
            Map<String, Object> measured = toMeasured(row);
            if (measured.isEmpty()) {
                continue;
            }
            Double tMs = num(row, "t_ms");
            WsEvents.ActionSample sample = new WsEvents.ActionSample(
                    sessionId, studentId, displayName, studentNo, a.actionType(), phaseName,
                    measured, a.identityConfidence(), a.confidence(), null, tMs, now);
            List<RuleHit> hits;
            try {
                hits = engine.evaluate(sample);
            } catch (RuntimeException e) {
                log.warn("直播规则评估异常 phase={}: {}", phaseName, e.getMessage());
                continue;
            }
            for (RuleHit h : hits) {
                if (h.safety()) {
                    publisher.publish(WsEventType.SAFETY_ALERT, new WsEvents.SafetyAlert(
                            h.eventId(), h.studentId(), h.displayName(), h.actionType(),
                            h.checkpointId(), h.cueText(), h.occurredAt()));
                } else {
                    publisher.publish(WsEventType.CUE, new WsEvents.Cue(
                            h.eventId(), h.studentId(), h.displayName(), h.actionType(),
                            h.checkpointId(), h.checkpointLabel(), h.severity().name(), h.cueText(),
                            Map.of(h.metric(), h.value()), h.confidence(), h.sourceCamera(), h.occurredAt()));
                }
                forwarder.enqueue(sessionId, h);
            }
        }
    }

    private WsEvents.ActionFocus toFocus(WsEvents.ActionFinalized a, UUID studentId, String studentNo, String displayName) {
        Map<String, Object> measured = new LinkedHashMap<>();
        if (a.made() != null) {
            measured.put("made", a.made());
        }
        return new WsEvents.ActionFocus(studentId, displayName, studentNo, a.actionType(), a.actionType(), measured);
    }

    /** action-clips 单条 body(字段对齐 IngestRequests.ClipItem);score 带出手相位角供云端派生标准度。 */
    private Map<String, Object> toClip(WsEvents.ActionFinalized a, UUID studentId, String studentNo, int clipIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (studentId != null) {
            m.put("studentId", studentId.toString());
        }
        if (studentNo != null) {
            m.put("studentNo", studentNo);
        }
        m.put("clipIndex", clipIndex);
        m.put("actionType", a.actionType());
        m.put("startMs", a.startMs());
        m.put("endMs", a.endMs());
        if (a.releaseMs() != null) {
            m.put("releaseMs", a.releaseMs());
        }
        if (a.phases() != null && !a.phases().isEmpty()) {
            m.put("phases", a.phases());
        }
        if (a.made() != null) {
            m.put("shotMade", a.made());
        }
        Map<String, Object> releaseAngles = releaseAngles(a);
        Map<String, Object> score = new LinkedHashMap<>();
        if (!releaseAngles.isEmpty()) {
            score.put("release_angles", releaseAngles);
            score.put("angles_source", "live_2d");   // 单机位近实时;精确标准度仍以课后批处理三维为准
        }
        if (a.confidence() != null) {
            score.put("confidence", a.confidence());
        }
        if (!score.isEmpty()) {
            m.put("score", score);
        }
        return m;
    }

    /** 出手相位角:取最靠近 release_ms 的一帧(供云端 mean_release_angles)。 */
    private Map<String, Object> releaseAngles(WsEvents.ActionFinalized a) {
        if (a.angles() == null || a.angles().isEmpty()) {
            return Map.of();
        }
        Double target = a.releaseMs() != null ? a.releaseMs() : a.endMs();
        Map<String, Object> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map<String, Object> row : a.angles()) {
            Double t = num(row, "t_ms");
            if (t == null) {
                continue;
            }
            double d = target == null ? 0 : Math.abs(t - target);
            if (d < bestDist) {
                bestDist = d;
                best = row;
            }
        }
        if (best == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        best.forEach((k, v) -> {
            if (!"t_ms".equals(k) && v instanceof Number) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** 算法 angle 键 → 检查点 metric 名(edge checkpoints.yaml 口径)。 */
    private static Map<String, Object> toMeasured(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        Double elbow = firstNum(row, "shooting_elbow", "right_elbow", "left_elbow");
        if (elbow != null) {
            m.put("elbow_angle", elbow);
        }
        Double rk = num(row, "right_knee");
        Double lk = num(row, "left_knee");
        Double knee = (rk != null && lk != null) ? Math.min(rk, lk) : (rk != null ? rk : lk);
        if (knee != null) {
            m.put("knee_angle", knee);
        }
        // 算法 v2.2.0 给了 shooting_wrist(标定后可信),用于出手压腕;2D 单视无效时算法给 null,自动跳过
        Double wrist = firstNum(row, "shooting_wrist", "right_wrist", "left_wrist");
        if (wrist != null) {
            m.put("wrist_angle", wrist);
        }
        return m;
    }

    /** 取落在 [phase.start,phase.end] 内、最靠相位中点的一帧 angles;无则该相位不评。 */
    private static Map<String, Object> representativeRow(List<Map<String, Object>> angles, Map<String, Object> phase) {
        Double ps = num(phase, "start_ms");
        Double pe = num(phase, "end_ms");
        if (ps == null || pe == null || angles == null) {
            return null;
        }
        double mid = (ps + pe) / 2.0;
        Map<String, Object> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map<String, Object> row : angles) {
            Double t = num(row, "t_ms");
            if (t == null || t < ps || t > pe) {
                continue;
            }
            double d = Math.abs(t - mid);
            if (d < bestDist) {
                bestDist = d;
                best = row;
            }
        }
        return best;
    }

    /** 解析算法 v2.2.0 的 action_finalized(snake_case)→ 内部记录。 */
    private WsEvents.ActionFinalized parseActionFinalized(JsonNode n) {
        JsonNode ident = n.path("identity");
        return new WsEvents.ActionFinalized(
                jstr(n, "session_id"),
                jstr(n, "student_id"),
                jstr(n, "global_id"),
                jstr(n, "action_type"),
                jdbl(n, "start_ms"),
                jdbl(n, "end_ms"),
                jdbl(n, "release_ms"),
                jbool(n, "made"),
                jdbl(n, "confidence"),
                ident.isObject() ? ident.path("confidence").asText(null) : null,
                ident.isObject() ? ident.path("source").asText(null) : null,
                jlist(n, "phases"),
                jlist(n, "angles"));
    }

    private List<Map<String, Object>> jlist(JsonNode n, String key) {
        JsonNode arr = n.get(key);
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(arr, new TypeReference<>() { });
    }

    private static String jstr(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static Double jdbl(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static Boolean jbool(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || !v.isBoolean() ? null : v.asBoolean();
    }

    /** 未绑定身份在投篮 → 发 ENROLL_NEEDED 提醒教师去输学号(同一身份 15s 去抖)。 */
    private void promptEnrollIfNew(WsEvents.ActionFinalized a) {
        String key = a.globalId() != null && !a.globalId().isBlank() ? a.globalId() : a.studentLocalId();
        if (key == null || key.isBlank()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Long last = lastEnrollPrompt.get(key);
        if (last != null && nowMs - last < ENROLL_PROMPT_COOLDOWN_MS) {
            return;
        }
        lastEnrollPrompt.put(key, nowMs);
        publisher.publish(WsEventType.ENROLL_NEEDED, new WsEvents.EnrollNeeded(
                a.studentLocalId(), a.globalId(), a.actionType(), OffsetDateTime.now()));
    }

    private UUID currentSessionId() {
        SessionResponse s = sessionService.current();
        return (s != null && (s.state() == SessionState.RECORDING || s.state() == SessionState.PAUSED))
                ? s.sessionId() : null;
    }

    private int nextClipIndex(UUID sessionId, String idKey) {
        return clipCounters.computeIfAbsent(sessionId + "|" + idKey, k -> new AtomicInteger(0)).getAndIncrement();
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Double firstNum(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Double v = num(row, k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Double num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
