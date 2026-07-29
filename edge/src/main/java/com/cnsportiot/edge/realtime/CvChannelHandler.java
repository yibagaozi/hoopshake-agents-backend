package com.cnsportiot.edge.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CV - edge 事件接收端(`/internal/cv/stream`)
 *
 * <p>CV 作为客户端连入,单向上行事件;edge 解析后发进程内事件,
 * 由 {@link WsHub} 扇出到大屏/操作台/注册页。**edge 不解析 payload 内部结构**——
 *
 * <p>上行帧:
 * <pre>
 * { "type": "poseFrame", "seq": 1042, "ts": "…", "sessionId": "…", "payload": { … } }
 * </pre>
 */
@Component
public class CvChannelHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CvChannelHandler.class);

    private final ObjectMapper objectMapper;
    private final EdgeEventPublisher publisher;

    /** 连接内的上一个 seq,用于丢帧检测;每次新连接重置 */
    private final AtomicLong lastSeq = new AtomicLong(-1);
    private final AtomicLong gapCount = new AtomicLong();
    private final AtomicLong receivedCount = new AtomicLong();

    private volatile boolean connected;
    private volatile Instant lastEventAt;

    public CvChannelHandler(ObjectMapper objectMapper, EdgeEventPublisher publisher) {
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    private static final int CV_TEXT_LIMIT = 256 * 1024;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(CV_TEXT_LIMIT);
        lastSeq.set(-1);
        gapCount.set(0);
        receivedCount.set(0);
        connected = true;
        log.info("CV 已接入 id={} remote={}", session.getId(), session.getRemoteAddress());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connected = false;
        log.warn("CV 断开 id={} status={} 本次共收 {} 条,丢帧 {} 条",
                session.getId(), status, receivedCount.get(), gapCount.get());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("CV 通道传输错误 id={}", session.getId(), exception);
    }

    /**
     * 这里只做解析与转发,不做 I/O
     *
     * <p>Spring 的 WS 接收跑在 IO 线程上,阻塞会卡住整条连接、让 CV 侧发送缓冲堆积。
     * {@link WsHub#broadcast} 内部已把实际发送交给 {@code ws-broadcast} 池,
     * 因此这里同步发布事件是安全的,不需要再套一层队列。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("CV 事件解析失败,已丢弃: {}", abbreviate(message.getPayload()), e);
            return;
        }

        String typeName = frame.path("type").asText(null);
        WsEventType type = WsEventType.fromWire(typeName);
        if (type == null) {
            log.warn("未知 CV 事件类型,已忽略: {}", typeName);
            return;
        }

        checkSeqGap(frame, type);
        receivedCount.incrementAndGet();
        lastEventAt = Instant.now();

        // payload 原样透传;WsHub 会重新序列化后扇出
        publisher.publish(type, frame.path("payload"));
    }

    /** 丢帧检测:骨架帧丢了只记数,业务事件丢了要告警——那意味着有反馈永久丢失 */
    private void checkSeqGap(JsonNode frame, WsEventType type) {
        if (!frame.hasNonNull("seq")) {
            return;
        }
        long seq = frame.get("seq").asLong();
        long prev = lastSeq.getAndSet(seq);
        if (prev < 0 || seq == prev + 1) {
            return;
        }
        long gap = seq - prev - 1;
        if (gap <= 0) {
            return;   // 乱序或重连后重新计数,忽略
        }
        gapCount.addAndGet(gap);
        if (type.droppable()) {
            log.debug("CV 事件跳号 {} 条(可丢类型)", gap);
        } else {
            log.error("CV 业务事件跳号 {} 条,存在永久丢失的反馈", gap);
        }
    }

    /** 供 /local/state 与操作台展示 CV 通道是否在线 */
    public boolean connected() {
        return connected;
    }

    public Instant lastEventAt() {
        return lastEventAt;
    }

    public long receivedCount() {
        return receivedCount.get();
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}

