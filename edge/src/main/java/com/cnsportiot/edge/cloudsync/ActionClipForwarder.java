package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.realtime.EdgeEvent;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import com.cnsportiot.edge.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "动作级"实时闭环接线(现阶段方案):订阅进程内 {@link EdgeEvent},只处理 {@link WsEventType#ACTION_EVENT}
 * ——算法每完成一个动作经 /internal/cv/stream 上行一条,edge:
 * <ul>
 *   <li>当场转 {@link WsEventType#ACTION_FOCUS} 上大屏(动作级提示:谁、什么动作、命中与否);</li>
 *   <li>作为<b>单条</b> action_clip 立即推云落库,供 agent 简易分析(计数/命中率/最近动作)。</li>
 * </ul>
 * 不跑课后批处理、不含角度指标(生物力学提示留下一阶段)。监听同步跑在发布线程,只做轻量解析 +
 * 发大屏 + 组包;网络推送交 {@code cloudIoExecutor} 异步,不阻塞 CV 上行链路。
 */
@Component
public class ActionClipForwarder {

    private static final Logger log = LoggerFactory.getLogger(ActionClipForwarder.class);

    private final EdgeEventPublisher publisher;
    private final CloudIngestClient cloud;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor cloudIoExecutor;

    /** (sessionId|身份) → 自增 clipIndex,算法未给 clipIndex 时用;保证 session+student+clipIndex 幂等键唯一。 */
    private final ConcurrentMap<String, AtomicInteger> clipCounters = new ConcurrentHashMap<>();

    public ActionClipForwarder(EdgeEventPublisher publisher, CloudIngestClient cloud,
                               SessionService sessionService, ObjectMapper objectMapper,
                               @Qualifier("cloudIoExecutor") TaskExecutor cloudIoExecutor) {
        this.publisher = publisher;
        this.cloud = cloud;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.cloudIoExecutor = cloudIoExecutor;
    }

    @EventListener
    public void onEdgeEvent(EdgeEvent event) {
        if (event.type() != WsEventType.ACTION_EVENT) {
            return;
        }
        WsEvents.ActionEvent a;
        try {
            a = objectMapper.convertValue(event.payload(), WsEvents.ActionEvent.class);
        } catch (RuntimeException e) {
            log.warn("actionEvent 解析失败,忽略: {}", e.getMessage());
            return;
        }

        // 1) 大屏动作级提示(即便无会话也照常显示)
        publisher.publish(WsEventType.ACTION_FOCUS, toFocus(a));

        // 2) 落库:需要会话 + 身份;缺任一只上屏不入库
        UUID sessionId = a.sessionId() != null ? a.sessionId() : currentSessionId();
        String idKey = a.studentId() != null ? a.studentId().toString() : a.studentNo();
        if (sessionId == null || idKey == null || idKey.isBlank()) {
            log.debug("actionEvent 缺会话或身份,只上屏不落库 session={} id={}", sessionId, idKey);
            return;
        }
        int clipIndex = a.clipIndex() != null ? a.clipIndex() : nextClipIndex(sessionId, idKey);
        Map<String, Object> item = toClip(a, clipIndex);
        cloudIoExecutor.execute(() -> {
            try {
                cloud.pushActionClips(sessionId, List.of(item));
            } catch (RuntimeException e) {
                log.warn("动作片段实时落库失败 session={} id={} clip={}: {}",
                        sessionId, idKey, clipIndex, e.getMessage());
            }
        });
    }

    private UUID currentSessionId() {
        SessionResponse s = sessionService.current();
        return (s != null && (s.state() == SessionState.RECORDING || s.state() == SessionState.PAUSED))
                ? s.sessionId() : null;
    }

    private int nextClipIndex(UUID sessionId, String idKey) {
        return clipCounters.computeIfAbsent(sessionId + "|" + idKey, k -> new AtomicInteger(0))
                .getAndIncrement();
    }

    private static WsEvents.ActionFocus toFocus(WsEvents.ActionEvent a) {
        Map<String, Object> measured = new LinkedHashMap<>();
        if (a.shotMade() != null) {
            measured.put("made", a.shotMade());
        }
        return new WsEvents.ActionFocus(
                a.studentId(), a.displayName(), a.studentNo(), a.actionType(), a.actionType(), measured);
    }

    /** 组装云端 action-clips 单条 body(字段名对齐 IngestRequests.ClipItem)。 */
    private static Map<String, Object> toClip(WsEvents.ActionEvent a, int clipIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a.studentId() != null) {
            m.put("studentId", a.studentId().toString());
        } else {
            m.put("studentNo", a.studentNo());   // 云端据学号解析 studentId
        }
        m.put("clipIndex", clipIndex);
        m.put("actionType", a.actionType());
        m.put("startMs", a.startMs());
        m.put("endMs", a.endMs());
        if (a.releaseMs() != null) {
            m.put("releaseMs", a.releaseMs());
        }
        if (a.sourceCamera() != null) {
            m.put("anchorCamera", a.sourceCamera());
        }
        if (a.phases() != null && !a.phases().isEmpty()) {
            m.put("phases", a.phases());
        }
        if (a.shotMade() != null) {
            m.put("shotMade", a.shotMade());
        }
        return m;
    }
}
