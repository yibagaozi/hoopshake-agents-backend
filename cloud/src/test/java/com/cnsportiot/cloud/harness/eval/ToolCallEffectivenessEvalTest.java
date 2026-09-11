package com.cnsportiot.cloud.harness.eval;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.audit.AuditService;
import com.cnsportiot.cloud.harness.hook.HookChain;
import com.cnsportiot.cloud.harness.hook.PostToolUseHook;
import com.cnsportiot.cloud.harness.hook.PreToolUseHook;
import com.cnsportiot.cloud.harness.hook.ResultRedactionHook;
import com.cnsportiot.cloud.harness.hook.StudentScopeGuardHook;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolRegistry;
import com.cnsportiot.cloud.harness.tool.ToolResult;
import com.cnsportiot.cloud.harness.tool.ToolRunner;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.impl.*;
import com.cnsportiot.cloud.harness.tool.port.PlaceholderStudentDataAdapter;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 工具调用效果评测(端到端、确定性):把"调用是否得到正确处置"量化为一条**工具正确率**,
 * 并守住选择安全(作用域隔离)与可选择性(spec 质量)。与 ToolRunnerTest 互补——后者查单点行为,
 * 这里查整体正确率与不变量。
 */
class ToolCallEffectivenessEvalTest {

    private static final UUID STUDENT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID STUDENT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT_A = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");

    private final StudentDataPort port = new PlaceholderStudentDataAdapter();

    private List<AgentTool> studentTools() {
        return List.of(
                new GetRecentClipsTool(port), new GetSessionSummaryTool(port),
                new GetInstantFeedbackLogTool(port), new GetProgressTrendTool(port),
                new GetActionDetailTool(port));
    }

    private List<AgentTool> teacherTools() {
        TeacherAnalyticsPort analytics = mock(TeacherAnalyticsPort.class);
        LessonEnrollmentRepository enroll = mock(LessonEnrollmentRepository.class);
        LessonRepository lesson = mock(LessonRepository.class);
        return List.of(
                new FindCommonIssuesTool(analytics, enroll),
                new GetGroupSummaryTool(analytics, enroll),
                new GetStudentSessionTool(analytics),
                new GetStudentTrendTool(analytics),
                new ListLessonStudentsTool(enroll),
                new ListMyLessonsTool(lesson, enroll),
                new ResolveStudentTool(enroll),
                new CompareToReferenceTool(analytics, new AgentProperties()));
    }

    private ToolRunner studentRunner(CapturingAudit audit) {
        ToolRegistry registry = new ToolRegistry(new ArrayList<>(studentTools()));
        HookChain hooks = new HookChain(
                List.<PreToolUseHook>of(new StudentScopeGuardHook(port)),
                List.<PostToolUseHook>of(new ResultRedactionHook(new AgentProperties())));
        return new ToolRunner(registry, hooks, audit);
    }

    // ---- 工具正确率:一批场景(选择+参数+闸门)得到的 Status 与期望一致的比例 ----

    @Test
    void toolCorrectnessRate_overGoldenScenarios() {
        CapturingAudit audit = new CapturingAudit();
        ToolRunner runner = studentRunner(audit);
        UUID ownSession = port.recentClips(STUDENT_A, 1).get(0).trainingSessionId();

        record Scenario(String tool, Map<String, Object> args, UUID student, ToolResult.Status expected) {}
        List<Scenario> golden = List.of(
                new Scenario("get_recent_clips", Map.of("limit", 3), STUDENT_A, ToolResult.Status.OK),
                new Scenario("get_progress_trend", Map.of(), STUDENT_A, ToolResult.Status.OK),
                new Scenario("get_instant_feedback_log", Map.of(), STUDENT_A, ToolResult.Status.OK),
                new Scenario("get_action_detail", Map.of("actionKey", "freethrow"), STUDENT_A, ToolResult.Status.OK),
                new Scenario("get_session_summary", Map.of("trainingSessionId", ownSession.toString()),
                        STUDENT_A, ToolResult.Status.OK),
                // 越权:指定他人 studentId
                new Scenario("get_recent_clips", foreignId(STUDENT_B), STUDENT_A, ToolResult.Status.DENIED),
                // 越权:拿他人会话 id
                new Scenario("get_session_summary", Map.of("trainingSessionId", UUID.randomUUID().toString()),
                        STUDENT_A, ToolResult.Status.DENIED),
                // 未知工具
                new Scenario("no_such_tool", Map.of(), STUDENT_A, ToolResult.Status.ERROR));

        List<ToolResult.Status> expected = new ArrayList<>();
        List<ToolResult.Status> actual = new ArrayList<>();
        for (Scenario s : golden) {
            expected.add(s.expected());
            actual.add(runner.run(s.tool(), s.args(), ToolContext.of(ACCOUNT_A, s.student()), null).status());
        }
        assertThat(EvalMetrics.accuracy(expected, actual))
                .as("tool-call correctness (actual=%s)", actual)
                .isEqualTo(1.0);   // 处置全部正确:OK/DENIED/ERROR 各归其位
    }

    private static Map<String, Object> foreignId(UUID other) {
        Map<String, Object> m = new HashMap<>();
        m.put("studentId", other.toString());
        return m;
    }

    // ---- 选择安全:作用域隔离,模型永远拿不到跨域工具 ----

    @Test
    void scopeIsolation_studentAndTeacherToolsDoNotLeak() {
        List<AgentTool> all = new ArrayList<>();
        all.addAll(studentTools());
        all.addAll(teacherTools());
        ToolRegistry registry = new ToolRegistry(all);   // 重名会在此抛错 → 同时验证 name 唯一

        assertThat(registry.byScope(ScopeKind.STUDENT)).hasSize(5)
                .allMatch(t -> t.scope() == ScopeKind.STUDENT);
        assertThat(registry.byScope(ScopeKind.TEACHER)).hasSize(8)
                .allMatch(t -> t.scope() == ScopeKind.TEACHER);
        assertThat(registry.byScope(ScopeKind.OPS)).isEmpty();   // 运维工具尚未接入
        assertThat(registry.all()).hasSize(13);
    }

    // ---- 可选择性:spec 质量(名字唯一/描述充分/schema 合法/只读)----

    @Test
    void specQuality_allToolsAreWellFormed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<AgentTool> all = new ArrayList<>();
        all.addAll(studentTools());
        all.addAll(teacherTools());

        for (AgentTool t : all) {
            ToolSpec spec = t.spec();
            assertThat(spec.name()).as("name").isNotBlank().matches("[a-z][a-z0-9_]*");   // 稳定函数名
            assertThat(spec.description()).as("desc of %s", spec.name()).isNotNull();
            assertThat(spec.description().length()).as("desc len of %s", spec.name()).isGreaterThan(10);
            assertThat(spec.displayLabel()).as("label of %s", spec.name()).isNotBlank();
            assertThat(spec.readOnly()).as("%s readOnly", spec.name()).isTrue();   // 阶段一全只读
            // inputSchema 必须是合法 JSON 且声明 object 类型(模型据此生成参数)
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = mapper.readValue(spec.inputSchema(), Map.class);
            assertThat(schema).as("schema of %s", spec.name()).containsEntry("type", "object");
        }
    }

    /** 捕获审计动作,不依赖数据库/Spring 代理(同 ToolRunnerTest)。 */
    private static final class CapturingAudit extends AuditService {
        final List<String> actions = new ArrayList<>();
        CapturingAudit() { super(null); }
        @Override public void record(UUID a, String action, UUID t, Map<String, Object> d) { actions.add(action); }
    }
}
