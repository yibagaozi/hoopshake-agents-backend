package com.cnsportiot.cloud.harness.tool.impl;

import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 群体工具的目标解析:把 {@code studentIds / lessonId / lessonIds / classCode} 统一展开成
 * 去重的 studentId 集合。归属已由 {@code TeacherScopeGuardHook} 前置校验
 */
final class TeacherTargets {

    private TeacherTargets() {}

    static List<UUID> resolve(Map<String, Object> args, UUID teacherId, LessonEnrollmentRepository enrollRepo) {
        Set<UUID> out = new LinkedHashSet<>();

        for (String v : strings(args.get("studentIds"))) {
            UUID id = uuid(v);
            if (id != null) {
                out.add(id);
            }
        }
        UUID lessonId = uuid(one(args.get("lessonId")));
        if (lessonId != null) {
            out.addAll(enrollRepo.findStudentIdsByLessonId(lessonId));
        }
        List<UUID> lessonIds = new ArrayList<>();
        for (String v : strings(args.get("lessonIds"))) {
            UUID id = uuid(v);
            if (id != null) {
                lessonIds.add(id);
            }
        }
        if (!lessonIds.isEmpty()) {
            out.addAll(enrollRepo.findStudentIdsByLessonIds(lessonIds));
        }
        String classCode = one(args.get("classCode"));
        if (classCode != null && !classCode.isBlank()) {
            out.addAll(enrollRepo.findStudentIdsByTeacherAndClassCode(teacherId, classCode.trim()));
        }
        return new ArrayList<>(out);
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (v != null && !String.valueOf(v).isBlank()) {
            out.add(String.valueOf(v).trim());
        }
        return out;
    }

    private static String one(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

