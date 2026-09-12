package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.domain.enums.SessionState;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.realtime.EdgeEvent;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import com.cnsportiot.edge.service.SessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 动作级实时闭环:actionEvent → 上大屏(actionFocus)+ 单条 action_clip 落库;无会话只上屏不落库。 */
class ActionClipForwarderTest {

    private static final UUID SID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID LESSON = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private ActionClipForwarder forwarder(EdgeEventPublisher pub, CloudIngestClient cloud, SessionService ss) {
        return new ActionClipForwarder(pub, cloud, ss, mapper, new SyncTaskExecutor());
    }

    private EdgeEvent actionEvent(Map<String, Object> payload) {
        return new EdgeEvent(WsEventType.ACTION_EVENT, payload);
    }

    private Map<String, Object> payload(String studentNo, String actionType, Boolean made) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentNo", studentNo);
        m.put("actionType", actionType);
        m.put("startMs", 4000.0);
        m.put("endMs", 6000.0);
        m.put("releaseMs", 5300.0);
        m.put("sourceCamera", "cam_03");
        if (made != null) {
            m.put("shotMade", made);
        }
        return m;   // sessionId 缺省 → edge 用当前会话补
    }

    private SessionResponse recording() {
        return new SessionResponse(SID, LESSON, SessionState.RECORDING, "dir", null, null, List.of(), List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeSession_showsFocus_andPersistsOneClip() {
        EdgeEventPublisher pub = mock(EdgeEventPublisher.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        SessionService ss = mock(SessionService.class);
        when(ss.current()).thenReturn(recording());

        forwarder(pub, cloud, ss).onEdgeEvent(actionEvent(payload("2021001", "free_throw", true)));

        verify(pub).publish(eq(WsEventType.ACTION_FOCUS), any(WsEvents.ActionFocus.class));
        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(cloud).pushActionClips(eq(SID), cap.capture());
        Map<String, Object> clip = cap.getValue().get(0);
        assertThat(clip).containsEntry("studentNo", "2021001")
                .containsEntry("actionType", "free_throw")
                .containsEntry("clipIndex", 0)
                .containsEntry("shotMade", true);
    }

    @Test
    void noActiveSession_showsFocus_butDoesNotPersist() {
        EdgeEventPublisher pub = mock(EdgeEventPublisher.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        SessionService ss = mock(SessionService.class);
        when(ss.current()).thenReturn(SessionResponse.idle());   // 无进行中的会话

        forwarder(pub, cloud, ss).onEdgeEvent(actionEvent(payload("2021001", "layup", null)));

        verify(pub).publish(eq(WsEventType.ACTION_FOCUS), any());
        verify(cloud, never()).pushActionClips(any(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clipIndex_autoIncrementsPerSessionStudent() {
        EdgeEventPublisher pub = mock(EdgeEventPublisher.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        SessionService ss = mock(SessionService.class);
        when(ss.current()).thenReturn(recording());
        ActionClipForwarder f = forwarder(pub, cloud, ss);

        f.onEdgeEvent(actionEvent(payload("2021001", "free_throw", true)));
        f.onEdgeEvent(actionEvent(payload("2021001", "free_throw", false)));

        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(cloud, times(2)).pushActionClips(eq(SID), cap.capture());
        assertThat(cap.getAllValues().get(0).get(0)).containsEntry("clipIndex", 0);
        assertThat(cap.getAllValues().get(1).get(0)).containsEntry("clipIndex", 1);
    }
}
