package com.cnsportiot.edge.rules;

import com.cnsportiot.edge.realtime.EdgeEvent;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import com.cnsportiot.edge.realtime.WsEvents.ActionSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 实时规则引擎接线:订阅进程内 {@link EdgeEvent},只处理 {@link WsEventType#ACTION_SAMPLE}——
 * 把 CV 上行的采样喂给纯 {@link RealtimeRuleEngine},命中即:
 * <ul>
 *   <li>发 {@link WsEventType#CUE}/{@link WsEventType#SAFETY_ALERT} 由 WsHub 扇出到大屏/操作台;</li>
 *   <li>入 {@link FeedbackForwarder} 缓冲,批量上云落 instant_feedback。</li>
 * </ul>
 * 事件监听同步跑在发布线程,故只做轻量解析 + 评估 + 入队,网络 I/O 交给 forwarder 的异步 flush。
 */
@Component
public class RuleEngineListener {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineListener.class);

    private final CheckpointProperties props;
    private final RealtimeRuleEngine engine;
    private final EdgeEventPublisher publisher;
    private final FeedbackForwarder forwarder;
    private final ObjectMapper objectMapper;

    public RuleEngineListener(CheckpointProperties props, RealtimeRuleEngine engine,
                              EdgeEventPublisher publisher, FeedbackForwarder forwarder,
                              ObjectMapper objectMapper) {
        this.props = props;
        this.engine = engine;
        this.publisher = publisher;
        this.forwarder = forwarder;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onEdgeEvent(EdgeEvent event) {
        if (!props.isEnabled() || event.type() != WsEventType.ACTION_SAMPLE) {
            return;
        }
        ActionSample sample;
        try {
            sample = objectMapper.convertValue(event.payload(), ActionSample.class);
        } catch (RuntimeException e) {
            log.warn("actionSample 解析失败,忽略: {}", e.getMessage());
            return;
        }

        List<RuleHit> hits;
        try {
            hits = engine.evaluate(sample);
        } catch (RuntimeException e) {
            log.error("实时规则评估异常 student={}", sample.studentId(), e);
            return;
        }

        for (RuleHit h : hits) {
            if (h.safety()) {
                publisher.publish(WsEventType.SAFETY_ALERT, toSafety(h));
            } else {
                publisher.publish(WsEventType.CUE, toCue(h));
            }
            forwarder.enqueue(sample.sessionId(), h);
        }
    }

    private static WsEvents.Cue toCue(RuleHit h) {
        return new WsEvents.Cue(
                h.eventId(), h.studentId(), h.displayName(), h.actionType(),
                h.checkpointId(), h.checkpointLabel(), h.severity().name(), h.cueText(),
                Map.of(h.metric(), h.value()), h.confidence(), h.sourceCamera(), h.occurredAt());
    }

    private static WsEvents.SafetyAlert toSafety(RuleHit h) {
        return new WsEvents.SafetyAlert(
                h.eventId(), h.studentId(), h.displayName(), h.actionType(),
                h.checkpointId(), h.cueText(), h.occurredAt());
    }
}
