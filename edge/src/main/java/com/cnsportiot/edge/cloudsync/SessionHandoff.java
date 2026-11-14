package com.cnsportiot.edge.cloudsync;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 算法侧在批处理结束时写的**云就绪交接文件**(默认 {@code {session}/cloud/ingest.json})。
 * 算法负责把课内 {@code stu_XX} 绑到 cloud 下发的 studentId(UUID)、算好出手相位角、给出各字段;
 * edge 只做:上传逐帧/特征到对象存储 → 回填 uri → 调 {@code /api/ingest/**}。时间戳用 ISO 字符串透传(cloud 解析)。
 */
public record SessionHandoff(
        SessionPart session,
        List<ClipPart> clips,
        List<GalleryPart> galleries) {

    /** 会话元信息(→ PUT /api/ingest/sessions/{id})。 */
    public record SessionPart(
            UUID lessonId,
            String status,                         // 云端 SessionStatus 名,如 SCORED
            Map<String, Object> coordinateSystem,
            String cameraConfigRef,
            String generatedAt,                    // ISO-8601 字符串
            String motionExportRelPath) {}         // 相对 session 目录,如 export/motion.jsonl

    /**
     * 动作片段(→ POST /api/ingest/action-clips;motion_uri 由 edge 上传后回填)。
     * 身份二选一:studentId(UUID)或 studentNo(学号)。算法自管人脸→学号绑定时给 studentNo,
     * 云端按学号解析成 studentId;edge 只透传,不关心人脸/绑定的具体结构。
     */
    public record ClipPart(
            UUID studentId,
            String studentNo,
            Integer clipIndex,
            String actionType,
            BigDecimal startMs,
            BigDecimal endMs,
            BigDecimal releaseMs,
            String anchorCamera,
            String zoneId,
            List<Map<String, Object>> phases,
            Boolean shotMade,
            /** 评分载荷;约定含 release_angles(joint→deg)+ angles_source,供云端派生 session_aggregate。 */
            Map<String, Object> score,
            Map<String, Object> motionRange) {}    // 在 motion 文件里的行/帧范围

    /** ReID gallery(→ POST /api/ingest/gallery/register;storage_uri 由 edge 上传后回填)。 */
    public record GalleryPart(
            UUID studentId,
            Integer version,
            String storageRelPath,                 // 相对 session 目录,指向打包好的特征文件
            String faceModel,
            String bodyModel,
            Integer faceDim,
            Integer bodyDim,
            Integer sampleCount,
            String enrolledCamera,
            String enrolledAt) {}                  // ISO-8601 字符串
}
