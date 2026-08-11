package com.cnsportiot.cloud.service;

import java.util.UUID;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 课堂实况 SSE 流服务。
 */
public interface LessonLiveStreamService {

    SseEmitter stream(UUID lessonId, UUID loginTeacherId);
}
