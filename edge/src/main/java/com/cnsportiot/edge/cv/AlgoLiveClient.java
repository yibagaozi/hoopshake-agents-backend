package com.cnsportiot.edge.cv;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 算法 v2.2.0 直播 WebSocket 客户端:edge 主动连算法的 WS 服务端(默认 {@code ws://127.0.0.1:8765/}),
 * 收 {@code action_finalized}/{@code timeline_gap} JSON,解析成 {@link WsEvents.ActionFinalized} 发进程内事件,
 * 由 {@link com.cnsportiot.edge.cloudsync.LiveActionListener} 消费(出 cue + 落库)。
 *
 * <p>方向与旧 {@code CvChannelHandler}(算法连入 edge)相反——v2.2.0 算法自己起 WS 服务,故 edge 作客户端。
 * 断线自动重连(JDK 原生 WebSocket,不引额外依赖)。仅 {@code hoopshake.edge.live.enabled=true} 时装配。
 */
@Component
@ConditionalOnProperty(prefix = "hoopshake.edge.live", name = "enabled", havingValue = "true")
public class AlgoLiveClient {

    private static final Logger log = LoggerFactory.getLogger(AlgoLiveClient.class);

    private final EdgeProperties props;
    private final EdgeEventPublisher publisher;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "algo-live-ws");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;
    private volatile WebSocket webSocket;

    public AlgoLiveClient(EdgeProperties props, EdgeEventPublisher publisher, ObjectMapper objectMapper) {
        this.props = props;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        log.info("算法直播 WS 客户端启动,连 {}", props.getLive().getWsUrl());
        connect();
    }

    @PreDestroy
    public void stop() {
        running = false;
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (RuntimeException ignore) {
                // 关闭尽力而为
            }
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        if (!running) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(props.getLive().getWsUrl());
        } catch (RuntimeException e) {
            log.error("算法直播 ws-url 非法: {}", props.getLive().getWsUrl());
            return;
        }
        httpClient.newWebSocketBuilder()
                .buildAsync(uri, new Handler())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warn("连算法 WS 失败,{}s 后重连: {}", reconnectSec(), rootMsg(err));
                        scheduleReconnect();
                    } else {
                        webSocket = ws;
                        log.info("已连上算法直播 WS {}", uri);
                    }
                });
    }

    private void scheduleReconnect() {
        if (running) {
            scheduler.schedule(this::connect, reconnectSec(), TimeUnit.SECONDS);
        }
    }

    private long reconnectSec() {
        long s = props.getLive().getReconnectInterval().toSeconds();
        return s <= 0 ? 3 : s;
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.toString();
    }

    /** 处理一条完整 JSON 帧。 */
    private void handle(String text) {
        JsonNode n;
        try {
            n = objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("算法直播事件解析失败,已丢弃: {}", e.getMessage());
            return;
        }
        String event = n.path("event").asText(null);
        if ("action_finalized".equals(event)) {
            publisher.publish(WsEventType.ACTION_FINALIZED, parseAction(n));
        } else if ("timeline_gap".equals(event)) {
            publisher.publish(WsEventType.TIMELINE_GAP, toMap(n));
        }
        // 其它事件类型忽略
    }

    private WsEvents.ActionFinalized parseAction(JsonNode n) {
        JsonNode ident = n.path("identity");
        return new WsEvents.ActionFinalized(
                str(n, "session_id"),
                str(n, "student_id"),
                str(n, "global_id"),
                str(n, "action_type"),
                dbl(n, "start_ms"),
                dbl(n, "end_ms"),
                dbl(n, "release_ms"),
                bool(n, "made"),
                dbl(n, "confidence"),
                ident.isObject() ? ident.path("confidence").asText(null) : null,
                ident.isObject() ? ident.path("source").asText(null) : null,
                list(n, "phases"),
                list(n, "angles"));
    }

    private Map<String, Object> toMap(JsonNode n) {
        return objectMapper.convertValue(n, new TypeReference<>() { });
    }

    private List<Map<String, Object>> list(JsonNode n, String key) {
        JsonNode arr = n.get(key);
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(arr, new TypeReference<>() { });
    }

    private static String str(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static Double dbl(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static Boolean bool(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || !v.isBoolean() ? null : v.asBoolean();
    }

    /** JDK WebSocket 监听:按 last 标志拼完整消息,demand=1 逐条拉。 */
    private final class Handler implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handle(msg);
                } catch (RuntimeException e) {
                    log.warn("处理算法直播事件异常: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("算法直播 WS 关闭 code={} reason={},{}s 后重连", statusCode, reason, reconnectSec());
            AlgoLiveClient.this.webSocket = null;
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("算法直播 WS 出错,{}s 后重连: {}", reconnectSec(), rootMsg(error));
            AlgoLiveClient.this.webSocket = null;
            scheduleReconnect();
        }
    }
}
