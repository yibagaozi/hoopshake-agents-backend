package com.cnsportiot.edge.realtime;

import com.cnsportiot.edge.service.CaptureService;
import com.cnsportiot.edge.service.SessionService;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 三个 WS 端点共用的处理器 */
@Component
public class EdgeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EdgeWebSocketHandler.class);

    private final WsHub hub;
    private final CaptureService captureService;
    private final SessionService sessionService;

    public EdgeWebSocketHandler(WsHub hub,
                                CaptureService captureService,
                                SessionService sessionService) {
        this.hub = hub;
        this.captureService = captureService;
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WsRole role = role(session);
        hub.register(role, session);
        sendSnapshot(session, role);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.unregister(role(session), session);
    }

    /** 客户端上行仅用于心跳保活,不承载业务 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.trace("WS 上行(忽略) id={} payload={}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输错误 id={}", session.getId(), exception);
        hub.unregister(role(session), session);
    }

    /** 接入快照:机位状态逐条推 + 会话状态一条 */
    private void sendSnapshot(WebSocketSession session, WsRole role) {
        captureService.state().cameras().forEach(cam ->
                hub.sendTo(session, WsEventType.CAMERA_STATUS, new WsEvents.CameraStatusEvent(
                        cam.camId(), cam.role(), cam.online(), cam.signal(), cam.fps())));

        SessionResponse s = sessionService.current();
        hub.sendTo(session, WsEventType.SESSION_STATUS, new WsEvents.SessionStatusEvent(
                s.sessionId(), s.lessonId(), s.state(), s.unavailableCameras()));
    }

    private static WsRole role(WebSocketSession session) {
        return WsRole.fromPath(session.getUri() == null ? null : session.getUri().getPath());
    }
}

