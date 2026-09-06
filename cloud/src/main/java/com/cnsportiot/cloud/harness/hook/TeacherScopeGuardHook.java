package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolInvocation;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.contracts.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 教师侧第二道闸(与 {@link StudentScopeGuardHook} 相反方向)
 * 教师可分析他人数据,但只能是自己名下课程的学生/课程/班级
 * 不剥除入参,而是逐一校验归属——不属于本教师则越权 {@code DATA_SCOPE_DENIED}(ToolRunner 记 TOOL_DENY)
 * 仅在 {@link ScopeKind#TEACHER} 上下文生效;学生上下文直通
 */
@Slf4j
@Component
@Order(0)
public class TeacherScopeGuardHook implements PreToolUseHook {

    private static final Set<String> STUDENT_KEYS = Set.of(
            "studentId", "student_id", "targetStudentId", "target_student_id");
    private static final Set<String> STUDENT_LIST_KEYS = Set.of("studentIds", "student_ids");
    private static final Set<String> LESSON_KEYS = Set.of("lessonId", "lesson_id");
    private static final Set<String> LESSON_LIST_KEYS = Set.of("lessonIds", "lesson_ids");
    private static final Set<String> CLASS_KEYS = Set.of("classCode", "class_code");

    private final LessonRepository lessonRepo;
    private final LessonEnrollmentRepository enrollRepo;

    public TeacherScopeGuardHook(LessonRepository lessonRepo, LessonEnrollmentRepository enrollRepo) {
        this.lessonRepo = lessonRepo;
        this.enrollRepo = enrollRepo;
    }

    @Override
    public void before(ToolInvocation inv) {
        if (inv.context().kind() != ScopeKind.TEACHER) {
            return;   // 学生侧由 StudentScopeGuardHook 处理
        }
        UUID teacher = inv.context().accountId();
        Map<String, Object> args = inv.args();

        for (String k : STUDENT_KEYS) {
            checkStudents(inv, teacher, values(args.get(k)));
        }
        for (String k : STUDENT_LIST_KEYS) {
            checkStudents(inv, teacher, values(args.get(k)));
        }
        for (String k : LESSON_KEYS) {
            checkLessons(inv, teacher, values(args.get(k)));
        }
        for (String k : LESSON_LIST_KEYS) {
            checkLessons(inv, teacher, values(args.get(k)));
        }
        for (String k : CLASS_KEYS) {
            for (String code : values(args.get(k))) {
                if (!lessonRepo.existsByClassCodeAndTeacherId(code, teacher)) {
                    deny(inv, "classCode", code);
                }
            }
        }
    }

    private void checkStudents(ToolInvocation inv, UUID teacher, List<String> vals) {
        for (String v : vals) {
            UUID sid = parse(v);
            if (sid == null || !enrollRepo.existsStudentUnderTeacher(teacher, sid)) {
                deny(inv, "studentId", v);
            }
        }
    }

    private void checkLessons(ToolInvocation inv, UUID teacher, List<String> vals) {
        for (String v : vals) {
            UUID lid = parse(v);
            if (lid == null || !lessonRepo.existsByIdAndTeacherId(lid, teacher)) {
                deny(inv, "lessonId", v);
            }
        }
    }

    private void deny(ToolInvocation inv, String field, Object value) {
        log.warn("教师工具 {} 越权:{}={} 不属于教师 {}",
                inv.spec().name(), field, value, inv.context().accountId());
        throw BusinessException.dataScopeDenied();
    }

    /** 把入参值统一成字符串列表:支持单值 / 集合;空值返回空列表 */
    private static List<String> values(Object v) {
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (!String.valueOf(v).isBlank()) {
            out.add(String.valueOf(v).trim());
        }
        return out;
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

