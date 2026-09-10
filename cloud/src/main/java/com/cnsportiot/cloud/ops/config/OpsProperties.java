package com.cnsportiot.cloud.ops.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 运维层配置
 *
 * <pre>
 * hoopshake:
 *   ops:
 *     edge:
 *       online-within: 90s      # 距上次心跳 ≤ 此 → ONLINE
 *       offline-after: 10m      # 距上次心跳 > 此 → OFFLINE(其间为 STALE)
 *     agent:
 *       default-window-hours: 24
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.ops")
public class OpsProperties {

    @NestedConfigurationProperty
    private Edge edge = new Edge();

    @NestedConfigurationProperty
    private Agent agent = new Agent();

    @Getter
    @Setter
    public static class Edge {
        /** 距上次心跳不超过此时长视为在线 */
        private Duration onlineWithin = Duration.ofSeconds(90);
        /** 距上次心跳超过此时长视为离线;其间为 STALE */
        private Duration offlineAfter = Duration.ofMinutes(10);
    }

    @Getter
    @Setter
    public static class Agent {
        /** Agent 表现近窗聚合默认窗口(小时) */
        private int defaultWindowHours = 24;
    }
}
