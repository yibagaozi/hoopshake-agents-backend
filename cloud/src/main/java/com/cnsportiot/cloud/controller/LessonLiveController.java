package com.cnsportiot.cloud.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.LessonLiveStreamService;
import com.cnsportiot.contracts.common.ApiResponse;

@RestController
@RequestMapping("/api/teacher/lessons")
@RequireRole(Role.TEACHER)
public class LessonLiveController {

    private final LessonLiveStreamService liveStreamService;

    public LessonLiveController(LessonLiveStreamService liveStreamService) {
        this.liveStreamService = liveStreamService;
    }

    @GetMapping(value = "/{lessonId}/live/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID lessonId,
            @CurrentUser AuthUser me) {
        return liveStreamService.stream(lessonId, me.accountId());
    }
}
