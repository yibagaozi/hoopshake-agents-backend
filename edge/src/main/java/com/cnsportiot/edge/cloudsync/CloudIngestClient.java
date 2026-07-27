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
                    .uri("/api/ingest/reid/gallery?lessonId={id}", lessonId)
                    .retrieve()
                    .body(Map.class);

            if (envelope == null || !Integer.valueOf(0).equals(envelope.get("code"))) {
                throw new BusinessException(EdgeErrorCode.CLOUD_REJECTED,
                        envelope == null ? "空响应" : String.valueOf(envelope.get("message")));
            }
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            List<Map<String, Object>> students = (List<Map<String, Object>>) data.get("students");
            return students.stream().map(CloudIngestClient::toRosterEntry).toList();

        } catch (RestClientException e) {
            log.error("拉取名单失败 lessonId={}", lessonId, e);
            throw new BusinessException(EdgeErrorCode.CLOUD_UNREACHABLE, "拉取参课名单失败");
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
}
