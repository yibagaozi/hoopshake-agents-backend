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

    @NestedConfigurationProperty
    private Grafana grafana = new Grafana();

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

    /**
     * Grafana 看板嵌入。后端**只暴露数据源** {@code /actuator/prometheus} 供 Grafana 采集,
     * 不自己出图;面板建在你们自己的 Grafana 上,把嵌入地址配在这里,运维台直接 iframe 显示。
     *
     * <pre>
     * hoopshake:
     *   ops:
     *     grafana:
     *       embed-url: https://grafana.example.com/d-solo/abc/panel?orgId=1&amp;panelId=2&amp;kiosk
     *       dashboard-url: https://grafana.example.com/d/abc/hoopshake
     * </pre>
     *
     * 留空 = 未配置,接口回 {@code configured=false},前端据此隐藏"监控"入口而不是渲染一个空白 iframe。
     */
    @Getter
    @Setter
    public static class Grafana {
        /** 面板嵌入地址(建议用 Grafana 的 d-solo + kiosk 形式);留空=未配置 */
        private String embedUrl;
        /** 完整看板地址,供"在 Grafana 中打开"的外链;留空则前端不显示该入口 */
        private String dashboardUrl;
        /** iframe 默认高度(px),纯展示用,前端可忽略 */
        private int embedHeight = 600;
    }
}
