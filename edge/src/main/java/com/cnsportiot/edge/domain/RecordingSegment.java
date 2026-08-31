package com.cnsportiot.edge.domain;

import java.time.OffsetDateTime;

/** 一路机位的一段录制文件 */
public record RecordingSegment(
        String camId,
        String fileName,
        String filePath,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt) {

    public RecordingSegment closed(OffsetDateTime at) {
        return new RecordingSegment(camId, fileName, filePath, startedAt, at);
    }
}

