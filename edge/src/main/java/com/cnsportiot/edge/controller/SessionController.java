package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.cloudsync.SessionBatchOrchestrator;
import com.cnsportiot.edge.service.SessionService;
import com.cnsportiot.edge.dto.SessionDtos.RecordingHealth;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.SessionDtos.StartSessionRequest;
import com.cnsportiot.edge.dto.SessionDtos.StopSessionResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 上课与录制;状态机 IDLE → READY → RECORDING ⇄ PAUSED → ENDED */
@RestController
@RequestMapping("/local/session")
public class SessionController {

    private final SessionService sessionService;
    private final SessionBatchOrchestrator batchOrchestrator;

    public SessionController(SessionService sessionService,
                             SessionBatchOrchestrator batchOrchestrator) {
        this.sessionService = sessionService;
        this.batchOrchestrator = batchOrchestrator;
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

    /**
     * 手动:对该会话跑算法批处理并(成功后)出云。需已配 {@code hoopshake.edge.batch}。
     * 异步执行(批处理可长达数十分钟),立即返回 accepted;进度看服务日志 [batch]。
     */
    @PostMapping("/{sessionId}/process")
    public ApiResponse<Map<String, Object>> process(@PathVariable UUID sessionId) {
        batchOrchestrator.triggerProcess(sessionId);
        return ApiResponse.ok(accepted(sessionId, "process"));
    }

    /**
     * 手动:把已跑好的算法交接文件({@code cloud/ingest.json})直接出云(发 sessionProcessed,不跑批处理)。
     * 用于算法已在别处手工跑完、只想触发上云的场景。交接文件不存在 → 40915。
     */
    @PostMapping("/{sessionId}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable UUID sessionId) {
        batchOrchestrator.triggerPublish(sessionId);
        return ApiResponse.ok(accepted(sessionId, "publish"));
    }

    private static Map<String, Object> accepted(UUID sessionId, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId.toString());
        m.put("action", action);
        m.put("accepted", true);
        return m;
    }
}

