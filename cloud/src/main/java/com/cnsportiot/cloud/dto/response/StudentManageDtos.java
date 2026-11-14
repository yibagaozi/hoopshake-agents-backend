package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.contracts.enums.GalleryStatus;
import com.cnsportiot.contracts.enums.DominantHand;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 学生管理响应 DTO */
public final class StudentManageDtos {
    private StudentManageDtos() {}

    /** 6.1 建档 */
    public record RegisterStudentResponse(
            UUID studentId,
            UUID accountId,
            String studentNo,
            String username,
            boolean initialPassword) {}

    /**
     * 教师重置学生密码的结果。账号保持 ACTIVE(不退回待激活),密码重置回**本地配置**的初始密码
     * (hoopshake.student.initial-password;留空则为学号本身),故不回明文——教师照配置约定告知即可。
     * 不置"强制改密"标记(本轮不加 account 列):学生要改密自行走 POST /api/auth/password。
     */
    public record ResetPasswordResponse(
            UUID studentId,
            boolean resetToInitialPassword) {}


    /** 6.2 学生检索 */
    public record StudentBriefResponse(
            UUID studentId,
            String studentNo,
            String displayName,
            String gradeBand,
            DominantHand dominantHand,
            boolean galleryReady) {}

    /** 6.3 学生详情
     * @param accountStatus 账号状态,前端据此决定是否展示激活引导
     */
    public record StudentDetailResponse(
            UUID studentId,
            String studentNo,
            String displayName,
            DominantHand dominantHand,
            BigDecimal heightCm,
            BigDecimal legLengthCm,
            String gradeBand,
            ActiveGallerySummary activeGallery,
            AccountStatus accountStatus) {}

    public record ActiveGallerySummary(
            UUID galleryId,
            int version,
            GalleryStatus status,
            Integer sampleCount,
            OffsetDateTime enrolledAt) {}

    /** 6.5 学生数据统计 */
    public record StudentStatsResponse(
            UUID studentId,
            String displayName,
            long totalSessions,
            long totalClips,
            OffsetDateTime lastSessionAt,
            List<StudentActionStat> byAction) {}

    public record StudentActionStat(String actionType, long clipCount, BigDecimal madeRate, OffsetDateTime lastAt) {}
}

