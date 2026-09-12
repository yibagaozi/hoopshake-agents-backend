package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批处理出云 MQ 生产者(RabbitMQ)。发到 topic 交换机 {@code hoopshake.ingest},
 * 每条设 {@code messageId=UUID}(供云端 ingest_event 台账幂等)、持久化投递。
 * 仅当 {@code hoopshake.edge.ingest.mq.enabled=true} 装配;实时反馈不走此路(仍 HTTP)。
 *
 * <p>消息体与云端消费的 DTO 对齐(见 Cloud API §10.7):routing key 选队列,body 为 JSON。
 * 只发不声明队列(队列/绑定由云端 RabbitAdmin 声明);交换机由 {@code EdgeMqConfig} 声明,避免早启动时无目标。
 */
@Component
@ConditionalOnProperty(prefix = "hoopshake.edge.ingest.mq", name = "enabled", havingValue = "true")
public class MqIngestPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqIngestPublisher.class);

    public static final String RK_SESSION = "ingest.session";
    public static final String RK_ACTION_CLIPS = "ingest.action-clips";
    public static final String RK_GALLERY = "ingest.gallery";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchange;

    public MqIngestPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, EdgeProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.exchange = props.getIngest().getMq().getExchange();
    }

    /** 会话上报:body = {sessionId, request}。 */
    public void sendSession(UUID sessionId, Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId == null ? null : sessionId.toString());
        body.put("request", request);
        send(RK_SESSION, body);
    }

    /** 动作片段批量:body = {sessionId, items}(与 ActionClipBatchRequest 对齐)。 */
    public void sendActionClips(UUID sessionId, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId == null ? null : sessionId.toString());
        body.put("items", items);
        send(RK_ACTION_CLIPS, body);
    }

    /** gallery 登记:body = RegisterGalleryRequest。 */
    public void sendGallery(Map<String, Object> body) {
        send(RK_GALLERY, body);
    }

    private void send(String routingKey, Object body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            log.error("入库消息序列化失败 rk={}: {}", routingKey, e.getMessage());
            return;
        }
        String messageId = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend(exchange, routingKey, json, m -> {
            m.getMessageProperties().setMessageId(messageId);
            m.getMessageProperties().setContentType("application/json");
            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return m;
        });
        log.info("已发布入库消息 rk={} messageId={}", routingKey, messageId);
    }
}
