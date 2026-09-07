package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.eval.StandardnessScorer;
import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort;
import com.cnsportiot.cloud.harness.tool.port.TeacherAnalyticsPort.ActionAngleProfile;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 教师工具:把指定学生某动作的关节角度与基准运动员比对,给出动作标准度(0-100)
 * 安全:目标 studentId 由 {@code TeacherScopeGuardHook} 校验归属;基准运动员只由服务端配置
 * ({@code hoopshake.agent.baseline.student-id}),不接受模型入参指定,避免借"参照"越权读任意学生
 * 同源约束:target 与 baseline 角度来源不同(triangulated_3d vs pseudo3d_fallback)时不评标准度
 */
@Component
public class CompareToReferenceTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "compare_to_reference",
            "把指定学生某动作的关节角度与基准运动员对比,给出动作标准度(0-100)、逐关节偏差与最需纠正的关节。"
                    + "studentId 必填,actionType 默认 layup。只和标准基准比,不做学生间排名。",
            "正在与基准运动员比对动作标准度…",
            """
            {"type":"object","properties":{
              "studentId":{"type":"string","description":"目标学生 id"},
              "actionType":{"type":"string","description":"动作类型,默认 layup(如 layup / free_throw)"}
            },"required":["studentId"]}""");

    private final TeacherAnalyticsPort analytics;
    private final AgentProperties props;

    public CompareToReferenceTool(TeacherAnalyticsPort analytics, AgentProperties props) {
        this.analytics = analytics;
        this.props = props;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ScopeKind scope() {
        return ScopeKind.TEACHER;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        UUID studentId = ToolArgs.getUuid(args, "studentId");
        if (studentId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "studentId 缺失或非法");
        }
        String action = ToolArgs.getString(args, "actionType");
        if (action == null) {
            action = "layup";
        }

        AgentProperties.Baseline cfg = props.getBaseline();
        UUID refId = parseUuid(cfg.getStudentId());
        if (refId == null) {
            return note(studentId, action, "未配置基准运动员(hoopshake.agent.baseline.student-id),无法评标准度");
        }

        ActionAngleProfile target = analytics.actionAngleProfile(studentId, action);
        if (target.angles() == null || target.angles().isEmpty()) {
            return note(studentId, action, "该学生暂无「" + action + "」可比的出手角度数据");
        }
        ActionAngleProfile ref = analytics.actionAngleProfile(refId, action);
        if (ref.angles() == null || ref.angles().isEmpty()) {
            return note(studentId, action, "基准运动员暂无「" + action + "」数据,请先补采/生成基准");
        }
        // 同源可比:两侧角度来源都已知且不同 不评(数据不可比)
        if (target.anglesSource() != null && ref.anglesSource() != null
                && !target.anglesSource().equalsIgnoreCase(ref.anglesSource())) {
            return note(studentId, action,
                    "数据不可比:角度来源不同(target=" + target.anglesSource() + ", baseline=" + ref.anglesSource() + ")");
        }

        // 基准:参考角度 + 默认容差带
        double tol = cfg.getDefaultToleranceDeg();
        Map<String, StandardnessScorer.JointRef> baseline = new LinkedHashMap<>();
        ref.angles().forEach((joint, mean) -> baseline.put(joint, new StandardnessScorer.JointRef(mean, tol)));

        StandardnessScorer.Result r = StandardnessScorer.score(
                target.angles(), baseline, cfg.getScaleDeg(),
                new java.util.HashSet<>(cfg.getDeadJoints()), null);

        String srcNote = (target.anglesSource() == null || ref.anglesSource() == null)
                ? "角度来源未标注,结果仅供参考" : null;
        return new CompareResult(
                studentId, action, r.standardness(), r.worstJoint(), r.inbandAll(),
                r.perJoint(), target.anglesSource(), ref.anglesSource(), srcNote);
    }

    private CompareResult note(UUID studentId, String action, String note) {
        return new CompareResult(studentId, action, null, null, false, List.of(), null, null, note);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 结果(可 Jackson 序列化,回注给模型 / 调试端点直出) */
    public record CompareResult(
            UUID studentId,
            String actionType,
            Integer standardness,                                  // 0..100;不可比时 null
            String worstJoint,                                     // 最需纠正的关节;全达标 null
            boolean inbandAll,
            List<StandardnessScorer.JointDeviation> perJoint,
            String targetAnglesSource,
            String referenceAnglesSource,
            String note) {}
}

