package com.cnsportiot.edge.dto;

import com.cnsportiot.edge.domain.enums.SessionState;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 上课与录制 DTO(局域网 API) */
public final class SessionDtos {

    private SessionDtos() {
    }

    /** 开始上课 */
    public record StartSessionRequest(UUID lessonId) {
    }

    /** 录制片段 */
    public record SegmentItem(
            String camId,
            String fileName,
            String filePath,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt) {
    }

    /** 会话状态统一响应 */
    public record SessionResponse(
            UUID sessionId,
            UUID lessonId,
            SessionState state,
            String dataDir,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            List<SegmentItem> segments,
            List<String> unavailableCameras) {

        public boolean degraded() {
            return unavailableCameras != null && !unavailableCameras.isEmpty();
        }

        public static SessionResponse idle() {
            return new SessionResponse(null, null, SessionState.IDLE,
                    null, null, null, List.of(), List.of());
        }

        public static SessionResponse ready(UUID lessonId) {
            return new SessionResponse(null, lessonId, SessionState.READY,
                    null, null, null, List.of(), List.of());
        }
    }

    /** 下课 */
    public record StopSessionResponse(
            UUID sessionId,
            SessionState state,
            OffsetDateTime endedAt,
            int segmentCount,
            List<SegmentItem> segments) {
    }

    /** 各路录制进程存活情况,供操作台发现某路中途挂掉 */
    public record RecordingHealth(
            SessionState state,
            boolean recording,
            Map<String, Boolean> aliveByCam) {
    }
}
