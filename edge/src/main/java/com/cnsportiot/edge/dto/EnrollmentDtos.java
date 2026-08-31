package com.cnsportiot.edge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** 现场姿态特征采集 DTO */
public final class EnrollmentDtos {

    private EnrollmentDtos() {
    }

    /** 启动采集 */
    public record StartEnrollRequest(
            @NotBlank
            @Pattern(regexp = "^\\d{10}$", message = "学号须为 10 位数字")
            String studentNo,
            /** 采集帧数,缺省 5 */
            Integer frames,
            /** 采集机位,缺省取主锚点 */
            String camId) {
    }

    /** 采集任务受理 */
    public record EnrollTaskResponse(
            UUID taskId,
            String studentNo,
            String camId,
            int frames,
            /** PENDING / CAPTURING / UPLOADING / REGISTERED / FAILED */
            String status) {
    }

    /** 采集进度 */
    public record EnrollProgressResponse(
            UUID taskId,
            String status,
            int capturedFrames,
            int totalFrames,
            /** 完成后回填,来自云端 §10.4 响应 */
            UUID galleryId,
            Integer galleryVersion,
            String failReason) {
    }
}

