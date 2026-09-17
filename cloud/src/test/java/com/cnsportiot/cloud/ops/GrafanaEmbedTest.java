package com.cnsportiot.cloud.ops;

import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.controller.OpsController;
import com.cnsportiot.cloud.ops.dto.OpsDtos.GrafanaEmbedResponse;
import com.cnsportiot.cloud.ops.service.OpsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Grafana 嵌入地址下发。后端不出图,只把配置好的地址交给前端 iframe;
 * <b>没配就要明确说"没配"</b>——否则前端会渲染一个空白 iframe,看着像监控挂了。
 */
class GrafanaEmbedTest {

    private OpsProperties props;
    private OpsController controller;

    @BeforeEach
    void setup() {
        props = new OpsProperties();
        controller = new OpsController(mock(OpsService.class), props);
    }

    private GrafanaEmbedResponse get() {
        return controller.grafana().data();
    }

    @Test void notConfigured_reportsConfiguredFalse_andNullUrls() {
        GrafanaEmbedResponse r = get();
        assertThat(r.configured()).isFalse();
        assertThat(r.embedUrl()).isNull();
        assertThat(r.dashboardUrl()).isNull();
    }

    /** 空白串也算没配,不能当成"配了一个空地址"发给前端。 */
    @Test void blankEmbedUrl_isTreatedAsNotConfigured() {
        props.getGrafana().setEmbedUrl("   ");
        assertThat(get().configured()).isFalse();
        assertThat(get().embedUrl()).isNull();
    }

    @Test void configured_returnsEmbedUrl() {
        props.getGrafana().setEmbedUrl("https://grafana.example.com/d-solo/abc?panelId=2&kiosk");
        props.getGrafana().setDashboardUrl("https://grafana.example.com/d/abc/hoopshake");
        props.getGrafana().setEmbedHeight(800);

        GrafanaEmbedResponse r = get();
        assertThat(r.configured()).isTrue();
        assertThat(r.embedUrl()).contains("d-solo");
        assertThat(r.dashboardUrl()).contains("/d/abc/");
        assertThat(r.embedHeight()).isEqualTo(800);
    }

    /** 只配了嵌入地址、没配完整看板外链:外链给 null,前端据此不显示"在 Grafana 中打开"。 */
    @Test void embedOnly_dashboardUrlStaysNull() {
        props.getGrafana().setEmbedUrl("https://grafana.example.com/d-solo/abc");
        GrafanaEmbedResponse r = get();
        assertThat(r.configured()).isTrue();
        assertThat(r.dashboardUrl()).isNull();
    }

    /** 顺带把抓取路径告诉运维,便于核对 Prometheus 的 scrape 配置。 */
    @Test void alwaysReportsMetricsPath() {
        assertThat(get().metricsPath()).isEqualTo("/actuator/prometheus");
    }
}
