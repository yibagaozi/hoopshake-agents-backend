package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.realtime.EdgeEvent;
import com.cnsportiot.edge.realtime.WsEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * "会话结束出云"触发:订阅进程内 {@link EdgeEvent},只处理 {@link WsEventType#SESSION_PROCESSED}
 * (CV 批处理完成后经本机 /internal/cv/stream 上行,payload 含 sessionId),交 cloudIoExecutor 异步出云。
 * 监听同步跑在发布线程,故只解析 + 提交,实际读盘/上传/网络在 executor。
 */
@Component
public class SessionPublishListener {

    private static final Logger log = LoggerFactory.getLogger(SessionPublishListener.class);

    private final SessionCloudPublisher publisher;
    private final ObjectMapper objectMapper;
    private final TaskExecutor cloudIoExecutor;

    public SessionPublishListener(SessionCloudPublisher publisher, ObjectMapper objectMapper,
                                  @Qualifier("cloudIoExecutor") TaskExecutor cloudIoExecutor) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.cloudIoExecutor = cloudIoExecutor;
    }

    @EventListener
    public void onEdgeEvent(EdgeEvent event) {
        if (event.type() != WsEventType.SESSION_PROCESSED) {
            return;
        }
        UUID sessionId;
        try {
            JsonNode node = objectMapper.valueToTree(event.payload());
            String s = node.path("sessionId").asText(null);
            if (s == null || s.isBlank()) {
                log.warn("sessionProcessed 缺 sessionId,忽略");
                return;
            }
            sessionId = UUID.fromString(s.trim());
        } catch (RuntimeException e) {
            log.warn("sessionProcessed 解析失败,忽略: {}", e.getMessage());
            return;
        }
        cloudIoExecutor.execute(() -> {
            try {
                publisher.publish(sessionId);
            } catch (RuntimeException e) {
                log.error("会话出云异常 session={}", sessionId, e);
            }
        });
    }
}
