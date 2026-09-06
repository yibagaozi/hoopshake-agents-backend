package com.cnsportiot.cloud.harness.tool;

/**
 * 工具/上下文的作用域类型,决定走哪一道"第二道闸":
 * {@link #STUDENT}:学生自绑定——{@code StudentScopeGuardHook} 强制只认 {@code ctx.studentId()};</li>
 * {@link #TEACHER}:教师按课程归属——{@code TeacherScopeGuardHook} 校验入参里的studentId/lessonId/classCode 属于该教师,否则越权
 */
public enum ScopeKind {
    STUDENT,
    TEACHER
}
