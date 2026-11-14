package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStatusResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStartResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStopResponse;
import com.cnsportiot.edge.dto.RecordDtos.TodayRecordingsResponse;
import com.cnsportiot.edge.service.RecordService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 录制管理(Edge API §4)
 *
 * <p>与 {@code /local/session/*} 是同一套会话的两个视角:这里不要求先选课,
 * 未选课即纯录制({@code lessonId} 为 null),已选课则自动沿用当前课程。
 * {@code recordingId} 即 {@code sessionId}。
 */
@RestController
@RequestMapping("/local/record")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    /** 一键开始录制 */
    @PostMapping("/start")
    public ApiResponse<RecordStartResponse> start() {
        return ApiResponse.ok(recordService.start());
    }

    /** 结束录制 */
    @PostMapping("/stop")
    public ApiResponse<RecordStopResponse> stop() {
        return ApiResponse.ok(recordService.stop());
    }

    /** 录制状态,WS record_status 之外的轮询兜底 */
    @GetMapping("/status")
    public ApiResponse<RecordStatusResponse> status() {
        return ApiResponse.ok(recordService.status());
    }

    /** 今日录制记录;dir 可省,缺省取 data-root */
    @GetMapping("/today")
    public ApiResponse<TodayRecordingsResponse> today(
            @RequestParam(required = false) String dir) {
        return ApiResponse.ok(recordService.today(dir));
    }
}

