package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 批处理 MQ 生产者:交换机/路由键/消息体/幂等 messageId/持久化。 */
class MqIngestPublisherTest {

    private static final UUID SID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private RabbitTemplate rt;
    private MqIngestPublisher pub;

    @BeforeEach
    void setup() {
        rt = mock(RabbitTemplate.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        pub = new MqIngestPublisher(rt, mapper, new EdgeProperties());   // 默认交换机 hoopshake.ingest
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Object>[] capture() {
        ArgumentCaptor<String> ex = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rk = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> pp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rt).convertAndSend(ex.capture(), rk.capture(), body.capture(), pp.capture());
        return new ArgumentCaptor[]{ex, rk, body, pp};
    }

    @Test
    void sendActionClips_toExchangeWithRkBodyAndIdempotentMessageId() throws Exception {
        pub.sendActionClips(SID, List.of(Map.of("clipIndex", 0, "actionType", "free_throw")));

        ArgumentCaptor<Object>[] c = capture();
        assertThat(c[0].getValue()).isEqualTo("hoopshake.ingest");
        assertThat(c[1].getValue()).isEqualTo("ingest.action-clips");
        assertThat((String) c[2].getValue()).contains("\"sessionId\"").contains(SID.toString())
                .contains("\"items\"").contains("free_throw");

        // 后处理器设 messageId(幂等键)+ contentType
        MessagePostProcessor pp = (MessagePostProcessor) c[3].getValue();
        Message out = pp.postProcessMessage(MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).build());
        assertThat(out.getMessageProperties().getMessageId()).isNotBlank();
        assertThat(out.getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    void sendSession_wrapsRequestUnderSessionId() {
        pub.sendSession(SID, Map.of("status", "SCORED"));
        ArgumentCaptor<Object>[] c = capture();
        assertThat(c[1].getValue()).isEqualTo("ingest.session");
        assertThat((String) c[2].getValue()).contains("\"sessionId\"").contains("\"request\"").contains("SCORED");
    }

    @Test
    void sendGallery_toGalleryRoutingKey() {
        pub.sendGallery(Map.of("studentId", SID.toString(), "version", 2));
        ArgumentCaptor<Object>[] c = capture();
        assertThat(c[1].getValue()).isEqualTo("ingest.gallery");
        assertThat((String) c[2].getValue()).contains("version");
    }
}
