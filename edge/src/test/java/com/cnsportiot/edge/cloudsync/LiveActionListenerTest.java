package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.contracts.enums.FeedbackSeverity;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.identity.IdentityBindingStore.Binding;
import com.cnsportiot.edge.realtime.EdgeEvent;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.rules.CheckpointProperties;
import com.cnsportiot.edge.rules.FeedbackForwarder;
import com.cnsportiot.edge.rules.RealtimeRuleEngine;
import com.cnsportiot.edge.rules.RuleHit;
import com.cnsportiot.edge.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 直播动作接线:action_finalized(snake_case)→ 身份绑定 → 大屏 + 规则出 cue + 落库(带 release_angles)。 */
class LiveActionListenerTest {

    private static final UUID SID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID STU = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private EdgeProperties props(Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        return p;
    }

    private SessionResponse recording() {
        return new SessionResponse(SID, null, SessionState.RECORDING, "dir", null, null, List.of(), List.of());
    }

    private Map<String, Object> payload() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("confidence", "high");
        identity.put("source", "face_gallery");
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("name", "release");
        phase.put("start_ms", 2400.0);
        phase.put("end_ms", 2600.0);
        Map<String, Object> angle = new LinkedHashMap<>();
        angle.put("t_ms", 2500.0);
        angle.put("shooting_elbow", 150.0);
        angle.put("right_knee", 120.0);
        angle.put("left_knee", 118.0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", "live_x");
        m.put("student_id", "stu_00");
        m.put("global_id", "stu_global_03");
        m.put("action_type", "free_throw");
        m.put("start_ms", 1000.0);
        m.put("end_ms", 3000.0);
        m.put("release_ms", 2500.0);
        m.put("made", true);
        m.put("confidence", 0.8);
        m.put("identity", identity);
        m.put("phases", List.of(phase));
        m.put("angles", List.of(angle));
        return m;
    }

    private RuleHit elbowLowHit() {
        return new RuleHit("ev1", STU, "张三", "2021001", "free_throw",
                "ft.release.elbow", "出手肘伸展", FeedbackSeverity.MAJOR, false,
                "出手时肘没伸直,向上送到位", "elbow_angle", 150.0, 0.8, "cam_03", 2500.0, OffsetDateTime.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void actionFinalized_resolvesIdentity_showsFocus_emitsCue_persistsClip(@TempDir Path root) {
        EdgeProperties p = props(root);
        EdgeEventPublisher pub = mock(EdgeEventPublisher.class);
        SessionService ss = mock(SessionService.class);
        when(ss.current()).thenReturn(recording());
        RealtimeRuleEngine engine = mock(RealtimeRuleEngine.class);
        when(engine.evaluate(any())).thenReturn(List.of(elbowLowHit()));
        FeedbackForwarder forwarder = mock(FeedbackForwarder.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);

        IdentityBindingStore bindings = new IdentityBindingStore(p, mapper);
        bindings.load();
        bindings.bind("stu_00", null, new Binding("2021001", STU.toString(), "张三"));

        LiveActionListener listener = new LiveActionListener(
                pub, bindings, ss, engine, new CheckpointProperties(), forwarder, cloud,
                new SyncTaskExecutor(), p, mapper);

        listener.onEdgeEvent(new EdgeEvent(WsEventType.ACTION_FINALIZED, payload()));

        // 大屏聚焦 + 由命中产出的 cue
        verify(pub).publish(eq(WsEventType.ACTION_FOCUS), any());
        verify(pub).publish(eq(WsEventType.CUE), any());
        verify(forwarder).enqueue(eq(SID), any(RuleHit.class));

        // 落库:studentNo + studentId + score.release_angles
        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(cloud).pushActionClips(eq(SID), cap.capture());
        Map<String, Object> clip = cap.getValue().get(0);
        assertThat(clip).containsEntry("studentNo", "2021001")
                .containsEntry("actionType", "free_throw")
                .containsEntry("shotMade", true)
                .containsEntry("clipIndex", 0);
        Map<String, Object> score = (Map<String, Object>) clip.get("score");
        assertThat(score).containsEntry("angles_source", "live_2d");
        Map<String, Object> release = (Map<String, Object>) score.get("release_angles");
        assertThat(release).containsKeys("shooting_elbow", "right_knee");
    }

    @Test
    void actionFinalized_noSession_showsFocus_butNoPersist(@TempDir Path root) {
        EdgeProperties p = props(root);
        EdgeEventPublisher pub = mock(EdgeEventPublisher.class);
        SessionService ss = mock(SessionService.class);
        when(ss.current()).thenReturn(SessionResponse.idle());
        RealtimeRuleEngine engine = mock(RealtimeRuleEngine.class);
        when(engine.evaluate(any())).thenReturn(List.of());
        CloudIngestClient cloud = mock(CloudIngestClient.class);

        IdentityBindingStore bindings = new IdentityBindingStore(p, mapper);
        bindings.load();
        bindings.bind("stu_00", null, new Binding("2021001", STU.toString(), "张三"));

        LiveActionListener listener = new LiveActionListener(
                pub, bindings, ss, engine, new CheckpointProperties(),
                mock(FeedbackForwarder.class), cloud, new SyncTaskExecutor(), p, mapper);

        listener.onEdgeEvent(new EdgeEvent(WsEventType.ACTION_FINALIZED, payload()));

        verify(pub).publish(eq(WsEventType.ACTION_FOCUS), any());
        verify(cloud, never()).pushActionClips(any(), anyList());
    }
}
