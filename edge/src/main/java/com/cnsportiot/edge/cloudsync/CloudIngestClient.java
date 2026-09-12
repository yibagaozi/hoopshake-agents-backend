package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.RosterEntry;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 云端 /api/ingest 客户端,edge 出云的唯一边界 */
@Component
public class CloudIngestClient {

    private static final Logger log = LoggerFactory.getLogger(CloudIngestClient.class);
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final RestClient restClient;

    public CloudIngestClient(EdgeProperties props) {
        this.restClient = RestClient.builder()
                .baseUrl(props.getCloud().getBaseUrl())
                .defaultHeader(SERVICE_TOKEN_HEADER, props.getCloud().getServiceToken())
                .build();
    }

    /** 参课名单 + gallery 预拉取
     *
     * @throws BusinessException CLOUD_UNREACHABLE 网络不可达;CLOUD_REJECTED 云端返回非 0
     */
    @SuppressWarnings("unchecked")
    public List<RosterEntry> fetchRoster(UUID lessonId) {
        try {
            Map<String, Object> envelope = restClient.get()
                    .uri("/api/ingest/gallery/pull?lessonId={id}", lessonId)
                    .retrieve()
                    .body(Map.class);

            if (envelope == null || !Integer.valueOf(0).equals(envelope.get("code"))) {
                throw new BusinessException(EdgeErrorCode.CLOUD_REJECTED,
                        envelope == null ? "空响应" : String.valueOf(envelope.get("message")));
            }
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            List<Map<String, Object>> students = (List<Map<String, Object>>) data.get("students");
            return students.stream().map(CloudIngestClient::toRosterEntry).toList();

        } catch (RestClientResponseException e) {
            // 云端可达但返回了 HTTP 错误状态(404 路径错 / 401 令牌错 / 5xx),不是"不可达"
            log.error("拉取名单被云端拒绝 lessonId={} status={} body={}",
                    lessonId, e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BusinessException(EdgeErrorCode.CLOUD_REJECTED,
                    "拉取参课名单失败:云端返回 HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            // 纯 I/O:连接被拒/超时/DNS —— 才是真正的不可达
            log.error("拉取名单网络不可达 lessonId={}", lessonId, e);
            throw new BusinessException(EdgeErrorCode.CLOUD_UNREACHABLE, "拉取参课名单失败:云端不可达");
        }
    }

    @SuppressWarnings("unchecked")
    private static RosterEntry toRosterEntry(Map<String, Object> m) {
        Map<String, Object> g = (Map<String, Object>) m.get("gallery");
        return new RosterEntry(
                UUID.fromString((String) m.get("studentId")),
                (String) m.get("studentNo"),
                (String) m.get("displayName"),
                (String) m.get("dominantHand"),
                g == null ? null : UUID.fromString((String) g.get("galleryId")),
                g == null ? null : (Integer) g.get("version"),
                g == null ? null : (String) g.get("storageUri"),
                g == null ? null : (String) g.get("faceModel"),
                g == null ? null : (String) g.get("bodyModel"));
    }

    /** Session 生命周期上报 */
    public void reportSession(UUID sessionId, Map<String, Object> body) {
        try {
            restClient.put()
                    .uri("/api/ingest/sessions/{id}", sessionId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new BusinessException(EdgeErrorCode.CLOUD_UNREACHABLE, "Session 上报失败");
        }
    }

    /**
     * 即时反馈批量上云({@code POST /api/ingest/feedback},逐条以 eventId 幂等)。
     * {@code sessionId} 可空(实时时会话可能尚未建全,云端按 timestamp_ms 事后回填 clip)。
     *
     * @throws BusinessException CLOUD_UNREACHABLE 网络不可达
     */
    public void pushFeedback(UUID sessionId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (sessionId != null) {
            body.put("sessionId", sessionId.toString());
        }
        body.put("items", items);
        try {
            restClient.post()
                    .uri("/api/ingest/feedback")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new BusinessException(EdgeErrorCode.CLOUD_UNREACHABLE, "即时反馈上报失败");
        }
    }

    /**
     * 动作片段批量上云({@code POST /api/ingest/action-clips},以 session+student+clipIndex 幂等)。
     * 课后批处理产物出云主通道;云端据此派生 session_aggregate。
     *
     * @throws BusinessException CLOUD_UNREACHABLE 网络不可达
     */
    public void pushActionClips(UUID sessionId, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("sessionId", sessionId.toString());
        body.put("items", items);
        try {
            restClient.post()
                    .uri("/api/ingest/action-clips")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new BusinessException(EdgeErrorCode.CLOUD_UNREACHABLE, "动作片段上报失败");
        }
    }

    /**
     * ReID gallery 登记({@code POST /api/ingest/gallery/register},敏感操作,云端记 audit_log)。
     * 注册完成后调用,把 face/body 模型与维度、样本数、storageUri 登记到云端。
     *
     * @throws BusinessException CLOUD_UNREACHABLE 网络不可达
     */
    public void registerGallery(Map<String, Object> body) {
        try {
            restClient.post()
                    .uri("/api/ingest/gallery/register")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new BusinessException(EdgeErrorCode.CLOUD_UNREACHABLE, "gallery 登记失败");
        }
    }
}
