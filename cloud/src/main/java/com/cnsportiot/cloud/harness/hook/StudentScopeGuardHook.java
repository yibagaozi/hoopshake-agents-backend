package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.harness.tool.ToolInvocation;
import com.cnsportiot.cloud.harness.tool.port.StudentDataPort;
import com.cnsportiot.contracts.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 学生只能看到自己的数据
 * 身份强制注入:模型不得选择数据主体。入参里任何身份字段(studentId/studentNo/subjectId…)若与权威 {@code ctx.studentId()} 不一致判越权;
 * 一律从入参剥除,工具只认 {@code ctx.studentId()}
 * 资源归属校验:入参里的会话/片段 id 交 {@link StudentDataPort#owns} 判定,非本人资源判越权
 * 越权抛 {@code DATA_SCOPE_DENIED},由 ToolRunner 记 {@code TOOL_DENY} 审计
 */
@Slf4j
@Component
@Order(0)
public class StudentScopeGuardHook implements PreToolUseHook {

    /** 身份字段:模型绝不能借此指定他人。命中且值≠权威 studentId → 越权;无论如何都剥除 */
    private static final Set<String> SUBJECT_KEYS = Set.of(
            "studentId", "student_id", "studentNo", "student_no",
            "subjectId", "subject_id", "targetStudentId", "target_student_id");

    /** 资源 id 字段 → 归属类型 */
    private static final Map<String, StudentDataPort.ResourceType> RESOURCE_KEYS = Map.of(
            "trainingSessionId", StudentDataPort.ResourceType.SESSION,
            "sessionId", StudentDataPort.ResourceType.SESSION,
            "clipId", StudentDataPort.ResourceType.CLIP);

    private final StudentDataPort studentData;

    public StudentScopeGuardHook(StudentDataPort studentData) {
        this.studentData = studentData;
    }

    @Override
    public void before(ToolInvocation inv) {
        UUID me = inv.context().studentId();
        Map<String, Object> args = inv.args();

        // 1) 身份字段:发现模型伪造他人身份即越权;否则剥除
        for (String key : SUBJECT_KEYS) {
            Object v = args.remove(key);
            if (v != null && !String.valueOf(v).isBlank()
                    && !String.valueOf(v).equalsIgnoreCase(String.valueOf(me))) {
                log.warn("工具 {} 越权:入参试图指定他人身份 {}={}", inv.spec().name(), key, v);
                throw BusinessException.dataScopeDenied();
            }
        }

        // 2) 资源归属:会话/片段 id 必须属于当前学生
        for (Map.Entry<String, StudentDataPort.ResourceType> e : RESOURCE_KEYS.entrySet()) {
            Object v = args.get(e.getKey());
            if (v == null || String.valueOf(v).isBlank()) {
                continue;   // 省略即用"最近一次",由工具/port 兜底
            }
            if (!studentData.owns(me, e.getValue(), String.valueOf(v))) {
                log.warn("工具 {} 越权:{}={} 不属于学生 {}", inv.spec().name(), e.getKey(), v, me);
                throw BusinessException.dataScopeDenied();
            }
        }
    }
}

