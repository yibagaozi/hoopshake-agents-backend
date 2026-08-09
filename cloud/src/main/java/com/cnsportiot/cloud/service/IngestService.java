package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.IngestRequests.*;
import com.cnsportiot.cloud.dto.response.IngestDtos.*;

import java.util.UUID;

/** §10 服务间入库 */
public interface IngestService {

    /** §10.1 会话 upsert:状态机 + 序号比较 */
    IngestAckResponse upsertSession(UUID sessionId, UpsertSessionRequest request);

    /** §10.2 原始输出入库(幂等) */
    IngestAckResponse ingestSessionOutput(SessionOutputIngestRequest request);

    /** §10.3 即时反馈批量入库 */
    BatchAckResponse ingestFeedback(InstantFeedbackBatchRequest request);

    /** §10.4 gallery 特征注册 */
    GalleryRegisteredResponse registerGallery(RegisterGalleryRequest request);

    /** §10.5 gallery 拉取 */
    GalleryPullResponse pullGallery(UUID lessonId);

    /** §10.6 edge 心跳 */
    void heartbeat(EdgeHeartbeatRequest request);
}
