package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.service.CaptureService;
import com.cnsportiot.edge.dto.ConsoleDtos.StateResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 操作台状态与采集控制 */
@RestController
@RequestMapping("/local")
public class ConsoleController {

    private final CaptureService captureService;

    public ConsoleController(CaptureService captureService) {
        this.captureService = captureService;
    }

    /** 平板首屏全量快照 */
    @GetMapping("/state")
    public ApiResponse<StateResponse> state() {
        return ApiResponse.ok(captureService.state());
    }

    /** 重启采集;不带 camId 则四路全部重启 */
    @PostMapping("/capture/restart")
    public ApiResponse<Void> restartCapture(@RequestParam(required = false) String camId) {
        captureService.restart(camId);
        return ApiResponse.ok();
    }
}
