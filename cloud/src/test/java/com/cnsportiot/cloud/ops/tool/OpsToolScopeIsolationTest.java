package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.audit.AuditService;
import com.cnsportiot.cloud.harness.hook.HookChain;
import com.cnsportiot.cloud.harness.hook.PostToolUseHook;
import com.cnsportiot.cloud.harness.hook.PreToolUseHook;
import com.cnsportiot.cloud.harness.hook.ResultRedactionHook;
import com.cnsportiot.cloud.harness.hook.StudentScopeGuardHook;
import com.cnsportiot.cloud.harness.hook.TeacherScopeGuardHook;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.harness.tool.ToolResult;
import com.cnsportiot.cloud.harness.tool.ToolRunner;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import com.cnsportiot.cloud.ops.service.OpsService;
import com.cnsportiot.cloud.ops.config.OpsProperties;
import com.cnsportiot.cloud.ops.dto.OpsDtos.*;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OPS 作用域隔离:8 个运维工具恰好都是 OPS,不外泄到学生/教师作用域;且在 ToolRunner 全链路里
 * 两道 scope 闸(学生自绑定 / 教师归属)对 OPS 上下文天然放行<——即便入参夹带 studentId 也不被误伤。
 * 印证 docs/ops/ops-agent-design.md §1/§6 的安全结论
 */
class OpsToolScopeIsolationTest {

    private final OpsProperties opsProps = new OpsProperties();
    private final AgentProperties agentProps = new AgentProperties();

    private List<AgentTool> opsTools(OpsService ops) {
        return List.of(
                new GetSystemOverviewTool(ops, opsProps), new GetBusinessCountsTool(ops),
                new GetAgentQualityTool(ops, opsProps), new GetSystemHealthTool(ops),
                new ListEdgeDevicesTool(ops), new GetEdgeDeviceTool(ops),
                new LookupErrorCodeTool(), new GetRuntimeConfigTool(agentProps, opsProps));
    }

    private ToolContext opsCtx() {
        return ToolContext.ops(UUID.randomUUID(), UUID.randomUUID(), Tier.STANDARD);
    }

    @Test
    void allEightAreOpsScoped_andNotVisibleToStudentOrTeacher() {
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(opsTools(mock(OpsService.class))));
        assertThat(registry.byScope(ScopeKind.OPS)).hasSize(8)
                .allMatch(t -> t.scope() == ScopeKind.OPS);
        assertThat(registry.byScope(ScopeKind.STUDENT)).isEmpty();
        assertThat(registry.byScope(ScopeKind.TEACHER)).isEmpty();
        assertThat(registry.all()).hasSize(8);
    }

    @Test
    void studentAndTeacherGuards_passThroughOpsContext_evenWithForeignStudentIdArg() {
        OpsService ops = mock(OpsService.class);
        when(ops.overview(anyInt())).thenReturn(new OverviewResponse(OffsetDateTime.now(), null, null, null, null));
        EdgeDeviceResponse dev = new EdgeDeviceResponse("box-1", "n", "c", "ok",
                EdgeHealth.ONLINE, "1.0", "fw", "10.0.0.1", Map.of("cpu", 10), null, OffsetDateTime.now());
        when(ops.edgeDevice(any())).thenReturn(dev);

        ToolRegistry registry = new ToolRegistry(new ArrayList<>(opsTools(ops)));
        // 两道 scope 闸都挂上;若任一误伤 OPS,调用会被 DENIED
        HookChain hooks = new HookChain(
                List.<PreToolUseHook>of(
                        new StudentScopeGuardHook(mock(StudentDataPort.class)),
                        new TeacherScopeGuardHook(mock(LessonRepository.class), mock(LessonEnrollmentRepository.class))),
                List.<PostToolUseHook>of(new ResultRedactionHook(new AgentProperties())));
        ToolRunner runner = new ToolRunner(registry, hooks, new CapturingAudit());

        // 无参工具:直接放行
        assertThat(runner.run("get_system_overview", Map.of(), opsCtx(), null).status())
                .isEqualTo(ToolResult.Status.OK);

        // 恶意夹带他人 studentId:学生闸的身份剥除/教师闸的归属校验都只认各自 kind,OPS 下不触发 → 仍 OK
        Map<String, Object> sneaky = new HashMap<>();
        sneaky.put("deviceId", "box-1");
        sneaky.put("studentId", UUID.randomUUID().toString());
        assertThat(runner.run("get_edge_device", sneaky, opsCtx(), null).status())
                .isEqualTo(ToolResult.Status.OK);
    }

    /** 捕获审计动作,不依赖数据库/Spring 代理(同 ToolRunnerTest / ToolCallEffectivenessEvalTest)。 */
    private static final class CapturingAudit extends AuditService {
        CapturingAudit() { super(null); }
        @Override public void record(UUID a, String action, UUID t, Map<String, Object> d) { }
    }
}
