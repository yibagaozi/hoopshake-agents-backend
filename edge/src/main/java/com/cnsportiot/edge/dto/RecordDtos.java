package com.cnsportiot.edge.dto;

import com.cnsportiot.edge.domain.enums.RecordState;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 录制管理 DTO */
public final class RecordDtos {

    private RecordDtos() {
    }

    /** 单机位录制状态 */
    public record RecordCamera(
            String cameraId,
            /** RECORDING / FAILED —— 开录时离线的机位为 FAILED */
            String state,
            String file,
            String error) {

        public static RecordCamera recording(String cameraId, String file) {
            return new RecordCamera(cameraId, "RECORDING", file, null);
        }

        public static RecordCamera failed(String cameraId, String error) {
            return new RecordCamera(cameraId, "FAILED", null, error);
        }
    }

    /** 已落盘的录制文件 */
    public record RecordFile(
            String cameraId,
            String file,
            Long sizeBytes,
            Long durationSeconds) {
    }

    /** 4.1 开始录制 */
    public record RecordStartResponse(
            UUID recordingId,
            RecordState state,
            UUID sessionId,
            String outputDir,
            OffsetDateTime startedAt,
            List<RecordCamera> cameras) {
    }

    /** 4.2 结束录制 */
    public record RecordStopResponse(
            UUID recordingId,
            RecordState state,
            OffsetDateTime endedAt,
            long durationSeconds,
            String outputDir,
            List<RecordFile> files) {
    }

    /** 4.3 录制状态 */
    public record RecordStatusResponse(
            RecordState state,
            UUID recordingId,
            UUID sessionId,
            OffsetDateTime startedAt,
            long elapsedSeconds,
            List<RecordCamera> cameras) {

        public static RecordStatusResponse idle() {
            return new RecordStatusResponse(RecordState.IDLE, null, null, null, 0, List.of());
        }
    }

    /** 4.4 今日录制记录中的一条 */
    public record RecordingEntry(
            UUID recordingId,
            UUID lessonId,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            long durationSeconds,
            int cameraCount,
            /** true = 该记录仍在录制中 */
            boolean recording,
            String outputDir,
            List<RecordFile> files) {
    }

    /** 4.4 今日录制记录 */
    public record TodayRecordingsResponse(
            LocalDate date,
            String dir,
            List<RecordingEntry> records) {
    }
}

