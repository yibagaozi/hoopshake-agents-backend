package com.cnsportiot.cloud.event;

import java.util.UUID;

/** 教师课堂实况 SSE 事件 */
public class LessonLiveEvent {

    private final UUID lessonId;
    private final String eventType;
    private final Object payload;

    public LessonLiveEvent(UUID lessonId, String eventType, Object payload) {
        this.lessonId = lessonId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public UUID getLessonId() {
        return lessonId;
    }

    public String getEventType() {
        return eventType;
    }

    public Object getPayload() {
        return payload;
    }
}
