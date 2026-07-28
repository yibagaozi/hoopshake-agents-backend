package com.cnsportiot.edge.realtime;

import com.cnsportiot.edge.domain.enums.SessionState;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** WS 事件载荷模型 */
public final class WsEvents {

    private WsEvents() {
    }

    /**
     * 3D 骨架帧
     *
     * @param persons 画面内所有人;未被 ReID 识别的 studentId 为 null
     */
    public record PoseFrame(
            String camId,
            long frameNo,
            List<Person> persons) {

        /**
         * @param keypoints 17 点,顺序按 COCO;每点 [x, y, z, score]
         * @param bbox      [x, y, w, h],归一化到 0~1
         */
        public record Person(
                UUID studentId,
                String displayName,
                double confidence,
                List<double[]> keypoints,
                double[] bbox) {
        }
    }

    /** 当前聚焦的动作,大屏中央区用 */
    public record ActionFocus(
            UUID studentId,
            String displayName,
            String studentNo,
            String actionType,
            String actionLabel,
            /** 关键测量值,如 {"elbow_angle": 118, "deviation": 20} */
            java.util.Map<String, Object> measured) {
    }

    /** 即时反馈提示,结构对齐云端 §10.3 items[] */
    public record Cue(
            String eventId,
            UUID studentId,
            String displayName,
            String actionType,
            String checkpointId,
            String checkpointLabel,
            /** MINOR / MAJOR / POSITIVE */
            String severity,
            String cueText,
            java.util.Map<String, Object> measured,
            Double confidence,
            String sourceCamera,
            OffsetDateTime occurredAt) {
    }

    /** 安全告警:词表中 safety=true 的检查点触发 */
    public record SafetyAlert(
            String eventId,
            UUID studentId,
            String displayName,
            String actionType,
            String checkpointId,
            String message,
            OffsetDateTime occurredAt) {
    }

    /** 机位状态,与 GET /local/state 的 cameras[] 同口径 */
    public record CameraStatusEvent(
            String camId,
            String role,
            boolean online,
            boolean signal,
            double fps) {
    }

    /** 会话状态跃迁 */
    public record SessionStatusEvent(
            UUID sessionId,
            UUID lessonId,
            SessionState state,
            List<String> unavailableCameras) {
    }

    /** 现场注册采集进度 */
    public record EnrollProgressEvent(
            UUID taskId,
            String studentNo,
            String status,
            int capturedFrames,
            int totalFrames) {
    }
}

