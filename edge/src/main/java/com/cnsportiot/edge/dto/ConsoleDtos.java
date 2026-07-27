package com.cnsportiot.edge.dto;

import com.cnsportiot.edge.domain.enums.ProcessState;

import java.util.Map;

/** 操作台状态面板 DTO */
public final class ConsoleDtos {

    private ConsoleDtos() {
    }

    /** GET /local/state 全量快照 */
    public record StateResponse(
            String edgeId,
            LessonDtos.LessonContextResponse lesson,
            SessionDtos.SessionResponse session,
            List<CameraState> cameras,
            CaptureState capture,
            DiskState disk) {
    }

    /**
     * 机位状态
     *
     * @param online 帧号在阈值内推进
     * @param signal 在线且非黑屏;false 而 online=true 即"连着但黑屏"
     */
    public record CameraState(
            String camId,
            String role,
            boolean anchor,
            boolean online,
            boolean signal,
            double fps,
            ProcessState processState,
            String rtspUrl) {
    }

    public record CaptureState(
            ProcessState mediamtx,
            boolean recording,
            Map<String, ProcessState> processes) {
    }

    public record DiskState(
            long freeBytes,
            long totalBytes,
            /** 按当前四路码率估算的可录分钟数 */
            long estimatedRemainingMinutes) {
    }
}

