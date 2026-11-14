package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.harness.llm.Tier;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolInvocation;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.contracts.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** 教师作用域闸:归属校验(放行本教师名下,拒绝他人),且学生上下文直通 */
class TeacherScopeGuardHookTest {

    private static final UUID TEACHER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID OWNED_STUDENT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID FOREIGN_STUDENT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID OWNED_LESSON = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID FOREIGN_LESSON = UUID.fromString("22222222-0000-0000-0000-000000000002");

    private LessonRepository lessonRepo;
    private LessonEnrollmentRepository enrollRepo;
    private TeacherScopeGuardHook hook;

    @BeforeEach
    void setup() {
        lessonRepo = mock(LessonRepository.class);
        enrollRepo = mock(LessonEnrollmentRepository.class);
        lenient().when(enrollRepo.existsStudentUnderTeacher(TEACHER, OWNED_STUDENT)).thenReturn(true);
        lenient().when(enrollRepo.existsStudentUnderTeacher(TEACHER, FOREIGN_STUDENT)).thenReturn(false);
        lenient().when(lessonRepo.existsByIdAndTeacherId(OWNED_LESSON, TEACHER)).thenReturn(true);
        lenient().when(lessonRepo.existsByIdAndTeacherId(FOREIGN_LESSON, TEACHER)).thenReturn(false);
        hook = new TeacherScopeGuardHook(lessonRepo, enrollRepo);
    }

    private ToolInvocation inv(Map<String, Object> args, ToolContext ctx) {
        ToolSpec spec = ToolSpec.readOnly("t", "d", "l", "{}");
        return new ToolInvocation(spec, new HashMap<>(args), ctx);
    }

    private ToolContext teacher() {
        return ToolContext.teacher(TEACHER, null, Tier.STANDARD);
    }

    @Test
    void ownedStudent_passes() {
        assertThatCode(() -> hook.before(inv(Map.of("studentId", OWNED_STUDENT.toString()), teacher())))
                .doesNotThrowAnyException();
    }

    @Test
    void foreignStudent_denied() {
        assertThatThrownBy(() -> hook.before(inv(Map.of("studentId", FOREIGN_STUDENT.toString()), teacher())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownedLesson_passes_foreignLesson_denied() {
        assertThatCode(() -> hook.before(inv(Map.of("lessonId", OWNED_LESSON.toString()), teacher())))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.before(inv(Map.of("lessonId", FOREIGN_LESSON.toString()), teacher())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void studentIdsList_anyForeign_denied() {
        Map<String, Object> args = Map.of("studentIds",
                List.of(OWNED_STUDENT.toString(), FOREIGN_STUDENT.toString()));
        assertThatThrownBy(() -> hook.before(inv(args, teacher())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void studentContext_isPassthrough() {
        // 学生上下文不由本闸处理:即便带他人 studentId 也不抛(交给 StudentScopeGuardHook)
        ToolContext studentCtx = ToolContext.of(UUID.randomUUID(), OWNED_STUDENT);
        assertThatCode(() -> hook.before(inv(Map.of("studentId", FOREIGN_STUDENT.toString()), studentCtx)))
                .doesNotThrowAnyException();
    }

    @Test
    void malformedStudentId_denied() {
        assertThatThrownBy(() -> hook.before(inv(Map.of("studentId", "not-a-uuid"), teacher())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void noScopeArgs_passes() {
        assertThat(true).isTrue();
        assertThatCode(() -> hook.before(inv(Map.of("keyword", "张"), teacher())))
                .doesNotThrowAnyException();
    }
}

