package com.cnsportiot.edge.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 监听器抛异常不能传给 Spring:否则连接以 1011 关闭,之后的动作全部丢失 */
class CvChannelHandlerTest {

    private static TextMessage frame(long seq) {
        return new TextMessage("{\"type\":\"actionFinalized\",\"seq\":" + seq + ",\"payload\":{\"student_id\":\"stu_00\"}}");
    }

    @Test
    void listenerException_isContained_andNextMessageStillProcessed() throws Exception {
        EdgeEventPublisher publisher = mock(EdgeEventPublisher.class);
        doThrow(new IllegalStateException("listener bug")).doNothing()
                .when(publisher).publish(eq(WsEventType.ACTION_FINALIZED), any());
        CvChannelHandler handler = new CvChannelHandler(JsonMapper.builder().build(), publisher);
        WebSocketSession session = mock(WebSocketSession.class);

        assertThatCode(() -> handler.handleTextMessage(session, frame(1))).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleTextMessage(session, frame(2))).doesNotThrowAnyException();

        verify(publisher, times(2)).publish(eq(WsEventType.ACTION_FINALIZED), any());
        assertThat(handler.listenerErrorCount()).isEqualTo(1);
        assertThat(handler.receivedCount()).isEqualTo(2);
        verify(session, never()).close(any());
    }
}
