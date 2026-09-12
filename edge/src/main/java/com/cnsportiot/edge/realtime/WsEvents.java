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

    /**
     * 动作评测采样(CV 上行 → 实时规则引擎输入)。与 {@link ActionFocus}(大屏展示)分离:
     * 这条只为"逐相位判定"。{@code measured} 是单机位 2D 可算量,如 {@code {"elbow_angle":118,"knee_angle":100}};
     * 腕屈角需手部关键点,COCO-17 不含,故实时层不评腕角(见 docs/edge/realtime-rule-engine.md)。
     */
    public record ActionSample(
            UUID sessionId,
            UUID studentId,
            String displayName,
            String studentNo,
            String actionType,
            String phase,
            java.util.Map<String, Object> measured,
            /** high / medium / low */
            String identityConfidence,
            Double confidence,
            String sourceCamera,
            Double timestampMs,
            OffsetDateTime occurredAt) {
    }

    /**
     * 算法 v2.2.0 直播 {@code action_finalized} 的解析结果(edge 内部载荷)。
     * 身份是算法的 {@code stu_XX}(当堂)/ {@code globalId}(跨课次人脸);edge 经绑定表映射到学号/UUID。
     * {@code angles} 是该动作 [start,end] 区间的逐时刻关节角序列(每行 {t_ms, shooting_elbow, right_knee, ...})
     */
    public record ActionFinalized(
            String algoSessionId,
            String studentLocalId,
            String globalId,
            String actionType,
            Double startMs,
            Double endMs,
            Double releaseMs,
            Boolean made,
            Double confidence,
            String identityConfidence,
            String identitySource,
            List<java.util.Map<String, Object>> phases,
            List<java.util.Map<String, Object>> angles) {
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

    /**
     * 待绑定人脸提示(→ 操作台/注册页):直播里出现未绑学号的身份在投篮,提醒教师去 {@code /local/enroll/bind} 输学号。
     * enrollSession 便于前端直接拉该注册 session 的缩略图看脸;stu_XX 无缩略图时前端仅提示“有未登记面孔”。
     */
    public record EnrollNeeded(
            String studentLocalId,
            String globalId,
            String actionType,
            OffsetDateTime occurredAt) {
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

