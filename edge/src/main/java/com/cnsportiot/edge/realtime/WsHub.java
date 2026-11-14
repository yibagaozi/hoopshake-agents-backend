package com.cnsportiot.edge.realtime;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS 会话管理与扇出
 *
 * <p>每个连接用 {@link ConcurrentWebSocketSessionDecorator} 包一层:
 * WebSocketSession 本身非线程安全,而扇出发生在多线程;装饰器同时提供发送超时
 * 与缓冲上限,慢客户端不会把广播线程拖住。
 */
@Component
public class WsHub {

    private static final Logger log = LoggerFactory.getLogger(WsHub.class);

    /** 单次发送超时,超过认为客户端卡住 */
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    /** 单连接待发缓冲上限,打满即视为客户端跟不上 */
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final TaskExecutor wsBroadcastExecutor;

    private final Map<WsRole, Set<WebSocketSession>> sessions = new EnumMap<>(WsRole.class);
    private final AtomicLong seq = new AtomicLong();

    public WsHub(ObjectMapper objectMapper,
                 @Qualifier("wsBroadcastExecutor") TaskExecutor wsBroadcastExecutor) {
        this.objectMapper = objectMapper;
        this.wsBroadcastExecutor = wsBroadcastExecutor;
        for (WsRole role : WsRole.values()) {
            sessions.put(role, ConcurrentHashMap.newKeySet());
        }
    }

    public void register(WsRole role, WebSocketSession raw) {
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(
                raw, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.get(role).add(wrapped);
        log.info("WS 已接入 role={} id={} 当前 {} 个连接", role, raw.getId(), count(role));
    }

    public void unregister(WsRole role, WebSocketSession raw) {
        sessions.get(role).removeIf(s -> sessionIdOf(s).equals(raw.getId()));
        log.info("WS 已断开 role={} id={} 剩余 {} 个连接", role, raw.getId(), count(role));
    }

    /** 订阅进程内事件,按类型声明的目标角色扇出 */
    @EventListener
    public void onEdgeEvent(EdgeEvent event) {
        broadcast(event.type(), event.payload());
    }

    public void broadcast(WsEventType type, Object payload) {
        if (noListener(type)) {
            return;
        }
        WsEnvelope envelope = WsEnvelope.of(type, seq.incrementAndGet(), payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("WS 事件序列化失败 type={}", type, e);
            return;
        }
        wsBroadcastExecutor.execute(() -> send(type, json));
    }

    /** 单连接定向发送,用于接入时补发快照 */
    public void sendTo(WebSocketSession session, WsEventType type, Object payload) {
        try {
            WsEnvelope envelope = WsEnvelope.of(type, seq.incrementAndGet(), payload);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
        } catch (Exception e) {
            log.warn("WS 快照发送失败 id={}", session.getId(), e);
        }
    }

    private void send(WsEventType type, String json) {
        TextMessage message = new TextMessage(json);
        for (WsRole role : type.targets()) {
            for (WebSocketSession s : sessions.get(role)) {
                if (!s.isOpen()) {
                    sessions.get(role).remove(s);
                    continue;
                }
                try {
                    s.sendMessage(message);
                } catch (IOException | IllegalStateException e) {
                    // 缓冲打满或连接异常:可丢事件直接跳过,不可丢事件断开慢客户端
                    if (type.droppable()) {
                        log.trace("丢弃事件 type={} id={}", type, s.getId());
                    } else {
                        log.warn("客户端跟不上,断开 role={} id={} type={}", role, s.getId(), type);
                        closeQuietly(s);
                        sessions.get(role).remove(s);
                    }
                }
            }
        }
    }

    private boolean noListener(WsEventType type) {
        return type.targets().stream().allMatch(r -> sessions.get(r).isEmpty());
    }

    public int count(WsRole role) {
        return sessions.get(role).size();
    }

    private static String sessionIdOf(WebSocketSession s) {
        return s instanceof ConcurrentWebSocketSessionDecorator d ? d.getDelegate().getId() : s.getId();
    }

    private static void closeQuietly(WebSocketSession s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 已断开
        }
    }
}

