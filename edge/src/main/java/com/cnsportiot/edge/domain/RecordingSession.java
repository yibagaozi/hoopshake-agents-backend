package com.cnsportiot.edge.domain;

import com.cnsportiot.edge.domain.enums.SessionState;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一次录制会话。sessionId 由边缘生成 UUID */
public class RecordingSession {

    private final UUID sessionId;

    private final UUID lessonId;

    private final String dataDir;
    private final OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private SessionState state;

    /** 全部录制片段,按开始时间顺序;暂停继续会追加新段 */
    private final List<RecordingSegment> segments = new ArrayList<>();

    public RecordingSession(UUID sessionId, UUID lessonId, String dataDir, OffsetDateTime startedAt) {
        this.sessionId = sessionId;
        this.lessonId = lessonId;
        this.dataDir = dataDir;
        this.startedAt = startedAt;
        this.state = SessionState.RECORDING;
    }

    public void addSegments(List<RecordingSegment> newSegments) {
        segments.addAll(newSegments);
    }

    /** 关闭当前所有未闭合的片段 */
    public void closeOpenSegments(OffsetDateTime at) {
        segments.replaceAll(s -> s.endedAt() == null ? s.closed(at) : s);
    }

    public void pause(OffsetDateTime at) {
        closeOpenSegments(at);
        this.state = SessionState.PAUSED;
    }

    public void resume() {
        this.state = SessionState.RECORDING;
    }

    public void end(OffsetDateTime at) {
        closeOpenSegments(at);
        this.endedAt = at;
        this.state = SessionState.ENDED;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID lessonId() {
        return lessonId;
    }

    public String dataDir() {
        return dataDir;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public OffsetDateTime endedAt() {
        return endedAt;
    }

    public SessionState state() {
        return state;
    }

    public List<RecordingSegment> segments() {
        return List.copyOf(segments);
    }
}

