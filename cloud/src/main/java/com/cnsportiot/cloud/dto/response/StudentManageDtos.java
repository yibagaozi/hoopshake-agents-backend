package com.cnsportiot.cloud.dto.response;

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

    /** 6.2 学生检索 */
    public record StudentBriefResponse(
            UUID studentId,
            String studentNo,
            String displayName,
            String gradeBand,
            DominantHand dominantHand,
            boolean galleryReady) {}

    /** 6.3 学生详情 */
    public record StudentDetailResponse(
            UUID studentId,
            String studentNo,
            String displayName,
            DominantHand dominantHand,
            BigDecimal heightCm,
            BigDecimal legLengthCm,
            String gradeBand,
            ActiveGallerySummary activeGallery) {}

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

