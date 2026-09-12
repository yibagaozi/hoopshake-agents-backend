package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 边缘出云 MQ 装配。声明与云端一致的 topic 交换机(队列/绑定由云端 RabbitAdmin 声明),
 * 保证 edge 先启动时发布也有目标。仅当 {@code hoopshake.edge.ingest.mq.enabled=true} 生效。
 */
@Configuration
@ConditionalOnProperty(prefix = "hoopshake.edge.ingest.mq", name = "enabled", havingValue = "true")
public class EdgeMqConfig {

    @Bean
    TopicExchange ingestExchange(EdgeProperties props) {
        return new TopicExchange(props.getIngest().getMq().getExchange(), true, false);
    }
}
