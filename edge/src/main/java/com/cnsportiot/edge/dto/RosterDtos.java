package com.cnsportiot.edge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 参课名单相关 DTO */
public final class RosterDtos {

    private RosterDtos() {
    }

    /** 名单条目 */
    public record RosterItem(
            UUID studentId,
            String studentNo,
            String displayName,
            String dominantHand,
            /** false → 需现场注册采集特征 */
            boolean galleryReady) {
    }

    /** GET /local/roster */
    public record RosterResponse(
            UUID lessonId,
            OffsetDateTime syncedAt,
            int total,
            int galleryReadyCount,
            List<RosterItem> students) {
    }

    /** POST /local/roster/sync */
    public record SyncRosterRequest(
            @NotBlank(message = "lessonId 不能为空") String lessonId) {
    }

    /** POST /local/roster/match —— 现场注册按学号匹配 */
    public record MatchRequest(
            @NotBlank(message = "学号不能为空")
            @Pattern(regexp = "^\\d{10}$", message = "学号须为 10 位数字")
            String studentNo) {
    }

    /** 匹配结果;matched=false 表示该学号不在本课名单,需先由教师端建档并重新同步 */
    public record MatchResponse(
            boolean matched,
            RosterItem student) {
    }
}

