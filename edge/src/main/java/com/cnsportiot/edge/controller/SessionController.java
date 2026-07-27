package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.service.SessionService;
import com.cnsportiot.edge.dto.SessionDtos.RecordingHealth;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.SessionDtos.StartSessionRequest;
import com.cnsportiot.edge.dto.SessionDtos.StopSessionResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 上课与录制;状态机 IDLE → READY → RECORDING ⇄ PAUSED → ENDED */
@RestController
@RequestMapping("/local/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 开始上课:创建会话并拉起四路录制。已选课时可不带 lessonId */
    @PostMapping("/start")
    public ApiResponse<SessionResponse> start(@Valid @RequestBody(required = false)
                                              StartSessionRequest request) {
        StartSessionRequest req = request != null ? request : new StartSessionRequest(null);
        return ApiResponse.ok(sessionService.start(req));
    }

    /** 暂停:挂起录制,课未结束 */
    @PostMapping("/pause")
    public ApiResponse<SessionResponse> pause() {
        return ApiResponse.ok(sessionService.pause());
    }

    /** 继续:开新一段录制文件 */
    @PostMapping("/resume")
    public ApiResponse<SessionResponse> resume() {
        return ApiResponse.ok(sessionService.resume());
    }

    /** 下课:停止录制并收尾 */
    @PostMapping("/stop")
    public ApiResponse<StopSessionResponse> stop() {
        return ApiResponse.ok(sessionService.stop());
    }

    /** 当前会话状态 */
    @GetMapping
    public ApiResponse<SessionResponse> current() {
        return ApiResponse.ok(sessionService.current());
    }

    /** 各路录制进程存活情况 */
    @GetMapping("/health")
    public ApiResponse<RecordingHealth> health() {
        return ApiResponse.ok(sessionService.health());
    }
}

