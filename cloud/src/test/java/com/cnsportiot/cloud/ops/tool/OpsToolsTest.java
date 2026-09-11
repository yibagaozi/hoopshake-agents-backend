package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.dto.OpsDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 运维工具(scope=OPS)单元测试:委托 OpsService 正确、入参宽松解析、
 * lookup_error_code 命中/未命中、get_runtime_config 读活值、spec 质量
 */
class OpsToolsTest {

    private OpsService ops;
    private OpsProperties opsProps;
    private AgentProperties agentProps;

    private ToolContext ctx() {
        return ToolContext.ops(UUID.randomUUID(), UUID.randomUUID(), Tier.STANDARD);
    }

    @BeforeEach
    void setup() {
        ops = mock(OpsService.class);
        opsProps = new OpsProperties();      // 默认 window=24, online=90s, offline=10m
        agentProps = new AgentProperties();
    }

    // ---- 委托 + 无参 ----

    @Test void overview_delegatesWithDefaultWindow() {
        OverviewResponse resp = new OverviewResponse(OffsetDateTime.now(), null, null, null, null);
        when(ops.overview(24)).thenReturn(resp);
        Object out = new GetSystemOverviewTool(ops, opsProps).execute(Map.of(), ctx());
        assertThat(out).isSameAs(resp);
        verify(ops).overview(24);   // 用 OpsProperties 默认窗口
    }

    @Test void business_delegates() {
        BusinessResponse biz = new BusinessResponse(0, 0, null, 0, null);
        when(ops.business()).thenReturn(biz);
        assertThat(new GetBusinessCountsTool(ops).execute(Map.of(), ctx())).isSameAs(biz);
    }

    @Test void systemHealth_delegates() {
        SystemHealthResponse h = new SystemHealthResponse(false, null, null, null);
        when(ops.systemHealth()).thenReturn(h);
        assertThat(new GetSystemHealthTool(ops).execute(Map.of(), ctx())).isSameAs(h);
    }

    // ---- 入参解析 ----

    @Test void agentQuality_usesArgWindow_elseDefault() {
        AgentQualityResponse q = new AgentQualityResponse(0, 0, 0, null, 0, null, null, null, null);
        when(ops.agentQuality(anyInt())).thenReturn(q);
        GetAgentQualityTool tool = new GetAgentQualityTool(ops, opsProps);

        tool.execute(Map.of("windowHours", 48), ctx());
        tool.execute(Map.of(), ctx());                    // 缺省 → 默认 24
        tool.execute(Map.of("windowHours", "bad"), ctx()); // 非法 → 落回默认 24

        verify(ops).agentQuality(48);
        verify(ops, times(2)).agentQuality(24);
    }

    @Test void listEdgeDevices_parsesHealth_invalidBecomesNull() {
        when(ops.edgeDevices(any())).thenReturn(new EdgeDeviceListResponse(new EdgeSummary(0, 0, 0, 0), List.of()));
        ListEdgeDevicesTool tool = new ListEdgeDevicesTool(ops);

        tool.execute(Map.of("health", "offline"), ctx());   // 大小写不敏感
        tool.execute(Map.of("health", "bogus"), ctx());      // 非法 → null(全部)
        tool.execute(Map.of(), ctx());                       // 缺省 → null(全部)

        verify(ops).edgeDevices(EdgeHealth.OFFLINE);
        verify(ops, times(2)).edgeDevices(isNull());
    }

    @Test void getEdgeDevice_found_returnsResponse() {
        EdgeDeviceResponse d = new EdgeDeviceResponse("box-1", "一号盒子", "court-1", "ok",
                EdgeHealth.ONLINE, "1.0", "fw-1", "10.0.0.1", Map.of("cpu", 12), null, OffsetDateTime.now());
        when(ops.edgeDevice("box-1")).thenReturn(d);
        assertThat(new GetEdgeDeviceTool(ops).execute(Map.of("deviceId", "box-1"), ctx())).isSameAs(d);
    }

    @Test void getEdgeDevice_notFound_returnsNote() {
        when(ops.edgeDevice("ghost")).thenReturn(null);
        Object out = new GetEdgeDeviceTool(ops).execute(Map.of("deviceId", "ghost"), ctx());
        assertThat(out).isInstanceOf(GetEdgeDeviceTool.NotFound.class);
        assertThat(((GetEdgeDeviceTool.NotFound) out).found()).isFalse();
    }

    @Test void getEdgeDevice_missingId_paramInvalid() {
        assertThatThrownBy(() -> new GetEdgeDeviceTool(ops).execute(Map.of(), ctx()))
                .isInstanceOf(com.cnsportiot.contracts.error.BusinessException.class);
        verifyNoInteractions(ops);
    }

    // ---- lookup_error_code:直读枚举,命中/未命中/列全 ----

    @Test void lookupErrorCode_byNumber_hit() {
        Object out = new LookupErrorCodeTool().execute(Map.of("code", "40301"), ctx());
        assertThat(out).isInstanceOf(LookupErrorCodeTool.Info.class);
        LookupErrorCodeTool.Info info = (LookupErrorCodeTool.Info) out;
        assertThat(info.name()).isEqualTo("DATA_SCOPE_DENIED");
        assertThat(info.httpStatus()).isEqualTo(403);
    }

    @Test void lookupErrorCode_byName_hit() {
        Object out = new LookupErrorCodeTool().execute(Map.of("code", "rate_limited"), ctx());  // 大小写不敏感
        assertThat(((LookupErrorCodeTool.Info) out).code()).isEqualTo(42900);
    }

    @Test void lookupErrorCode_miss_returnsNotFound() {
        Object out = new LookupErrorCodeTool().execute(Map.of("code", "99999"), ctx());
        assertThat(out).isInstanceOf(LookupErrorCodeTool.NotFound.class);
        assertThat(((LookupErrorCodeTool.NotFound) out).found()).isFalse();
    }

    @Test void lookupErrorCode_noArg_listsAll() {
        Object out = new LookupErrorCodeTool().execute(Map.of(), ctx());
        assertThat(out).isInstanceOf(LookupErrorCodeTool.Listing.class);
        LookupErrorCodeTool.Listing all = (LookupErrorCodeTool.Listing) out;
        assertThat(all.count()).isEqualTo(com.cnsportiot.contracts.error.ErrorCode.values().length);
        assertThat(all.codes()).anyMatch(i -> i.code() == 50310 && i.name().equals("LLM_UNAVAILABLE"));
    }

    // ---- get_runtime_config:读活值(改配置后应反映)----

    @Test void runtimeConfig_readsLiveValues() {
        agentProps.getResilience().getRateLimit().setBurst(99);
        agentProps.getResilience().getCircuit().setFailureThreshold(7);
        Object out = new GetRuntimeConfigTool(agentProps, opsProps).execute(Map.of(), ctx());
        assertThat(out).isInstanceOf(GetRuntimeConfigTool.RuntimeConfig.class);
        GetRuntimeConfigTool.RuntimeConfig c = (GetRuntimeConfigTool.RuntimeConfig) out;
        assertThat(c.askRateLimit().burst()).isEqualTo(99);            // 活值,非文档快照
        assertThat(c.circuit().failureThreshold()).isEqualTo(7);
        assertThat(c.edge().onlineWithinSeconds()).isEqualTo(90);      // OpsProperties 默认
        assertThat(c.edge().offlineAfterSeconds()).isEqualTo(600);
        assertThat(c.agentDefaultWindowHours()).isEqualTo(24);
    }

    // ---- spec 质量:8 个 OPS 工具都规范、只读 ----

    @Test void specQuality_allOpsToolsWellFormed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<AgentTool> all = List.of(
                new GetSystemOverviewTool(ops, opsProps), new GetBusinessCountsTool(ops),
                new GetAgentQualityTool(ops, opsProps), new GetSystemHealthTool(ops),
                new ListEdgeDevicesTool(ops), new GetEdgeDeviceTool(ops),
                new LookupErrorCodeTool(), new GetRuntimeConfigTool(agentProps, opsProps));
        assertThat(all).hasSize(8);
        for (AgentTool t : all) {
            ToolSpec spec = t.spec();
            assertThat(t.scope()).as("%s scope", spec.name()).isEqualTo(ScopeKind.OPS);
            assertThat(spec.readOnly()).as("%s readOnly", spec.name()).isTrue();
            assertThat(spec.name()).as("name").matches("[a-z][a-z0-9_]*");
            assertThat(spec.description().length()).as("desc len of %s", spec.name()).isGreaterThan(10);
            assertThat(spec.displayLabel()).as("label of %s", spec.name()).isNotBlank();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = mapper.readValue(spec.inputSchema(), Map.class);
            assertThat(schema).as("schema of %s", spec.name()).containsEntry("type", "object");
        }
        // 名字唯一
        assertThat(all.stream().map(t -> t.spec().name()).distinct().count()).isEqualTo(8);
    }
}
