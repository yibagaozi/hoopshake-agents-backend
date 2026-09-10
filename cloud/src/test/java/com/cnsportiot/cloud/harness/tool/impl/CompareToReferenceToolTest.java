package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.impl.CompareToReferenceTool.CompareResult;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort.ActionAngleProfile;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** compare_to_reference 工具:标准度计算、基准来源(仅配置)、同源约束、缺数据/缺配置兜底、参数校验。 */
class CompareToReferenceToolTest {

    private static final UUID TARGET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID REF = UUID.fromString("cccccccc-0000-0000-0000-000000000003");   // stu_03
    private static final UUID TEACHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private TeacherAnalyticsPort port;
    private AgentProperties props;
    private CompareToReferenceTool tool;

    @BeforeEach
    void setup() {
        port = mock(TeacherAnalyticsPort.class);
        props = new AgentProperties();
        props.getBaseline().setStudentId(REF.toString());   // 基准运动员由服务端配置
        tool = new CompareToReferenceTool(port, props);
    }

    private ToolContext ctx() {
        return ToolContext.teacher(TEACHER, UUID.randomUUID(), Tier.STANDARD);
    }

    private static ActionAngleProfile profile(UUID id, String src, Map<String, Double> angles) {
        return new ActionAngleProfile(id, "layup", angles, src, 3, "test");
    }

    private static Map<String, Double> refAngles() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("right_elbow", 170.0);
        m.put("left_elbow", 165.0);
        m.put("right_knee", 160.0);
        m.put("left_knee", 160.0);
        m.put("right_wrist", 180.0);   // 死值,应被排除
        return m;
    }

    @Test void happy_scoresAgainstBaseline_picksWorst() {
        Map<String, Double> target = new LinkedHashMap<>(refAngles());
        target.put("right_elbow", 130.0);   // 差 40° → 超带、最差
        when(port.actionAngleProfile(eq(TARGET), any())).thenReturn(profile(TARGET, "triangulated_3d", target));
        when(port.actionAngleProfile(eq(REF), any())).thenReturn(profile(REF, "triangulated_3d", refAngles()));

        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());

        assertThat(r.standardness()).isNotNull().isLessThan(100).isGreaterThan(0);
        assertThat(r.worstJoint()).isEqualTo("right_elbow");
        assertThat(r.inbandAll()).isFalse();
        assertThat(r.perJoint()).noneMatch(d -> d.joint().equals("right_wrist"));   // 死腕不计
        assertThat(r.note()).isNull();
    }

    @Test void perfectMatch_is100() {
        when(port.actionAngleProfile(eq(TARGET), any())).thenReturn(profile(TARGET, "triangulated_3d", refAngles()));
        when(port.actionAngleProfile(eq(REF), any())).thenReturn(profile(REF, "triangulated_3d", refAngles()));
        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());
        assertThat(r.standardness()).isEqualTo(100);
        assertThat(r.inbandAll()).isTrue();
    }

    @Test void baselineNotConfigured_returnsNote() {
        props.getBaseline().setStudentId(null);
        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());
        assertThat(r.standardness()).isNull();
        assertThat(r.note()).contains("未配置基准");
    }

    @Test void sourceMismatch_notComparable() {
        when(port.actionAngleProfile(eq(TARGET), any())).thenReturn(profile(TARGET, "pseudo3d_fallback", refAngles()));
        when(port.actionAngleProfile(eq(REF), any())).thenReturn(profile(REF, "triangulated_3d", refAngles()));
        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());
        assertThat(r.standardness()).isNull();
        assertThat(r.note()).contains("不可比");
    }

    @Test void targetNoData_returnsNote() {
        when(port.actionAngleProfile(eq(TARGET), any())).thenReturn(profile(TARGET, null, Map.of()));
        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());
        assertThat(r.standardness()).isNull();
        assertThat(r.note()).contains("暂无");
    }

    @Test void baselineNoData_returnsNote() {
        when(port.actionAngleProfile(eq(TARGET), any())).thenReturn(profile(TARGET, "triangulated_3d", refAngles()));
        when(port.actionAngleProfile(eq(REF), any())).thenReturn(profile(REF, "triangulated_3d", Map.of()));
        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());
        assertThat(r.standardness()).isNull();
        assertThat(r.note()).contains("基准运动员暂无");
    }

    @Test void unknownSource_scoresWithCaveat() {
        // 两侧来源都未标注 → 仍打分,但带"仅供参考"提示
        when(port.actionAngleProfile(eq(TARGET), any())).thenReturn(profile(TARGET, null, refAngles()));
        when(port.actionAngleProfile(eq(REF), any())).thenReturn(profile(REF, null, refAngles()));
        CompareResult r = (CompareResult) tool.execute(Map.of("studentId", TARGET.toString()), ctx());
        assertThat(r.standardness()).isEqualTo(100);
        assertThat(r.note()).contains("仅供参考");
    }

    @Test void missingStudentId_paramInvalid() {
        assertThatThrownBy(() -> tool.execute(Map.of(), ctx()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }
}
