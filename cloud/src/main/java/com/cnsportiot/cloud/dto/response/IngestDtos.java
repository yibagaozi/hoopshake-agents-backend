package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.DominantHand;
import com.cnsportiot.cloud.domain.enums.GalleryStatus;
import com.cnsportiot.cloud.domain.enums.SessionStatus;

import java.util.List;
import java.util.UUID;

/** 服务间入库应答 DTO */
public final class IngestDtos {
    private IngestDtos() {}

    /** 10.1 Session 生命周期 / 10.2 SessionOutput */
    public record IngestAckResponse(
            String eventId,
            UUID sessionId,
            boolean applied,       // 状态回退等被忽略时 false
            boolean duplicated,    // 幂等命中(非错误)
            SessionStatus currentStatus) {}

    /** 10.3 即时反馈批量 */
    public record BatchAckResponse(int accepted, int duplicated, List<RejectedItem> rejected) {}

    public record RejectedItem(String eventId, String reason) {}

    /** 10.4 gallery 登记 */
    public record GalleryRegisteredResponse(UUID galleryId, UUID studentId, int version, GalleryStatus status) {}

    /** 10.5 参课名单 gallery 预拉取 */
    public record GalleryPullResponse(UUID lessonId, List<GalleryPullStudent> students) {}

    public record GalleryPullStudent(
            UUID studentId,
            String studentNo,
            String displayName,
            DominantHand dominantHand,
            GalleryRef gallery) {}   // 空表示尚未注册特征

    public record GalleryRef(UUID galleryId, int version, String storageUri, String faceModel, String bodyModel) {}
}
