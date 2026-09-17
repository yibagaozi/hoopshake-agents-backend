package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.calibration.CalibrationService;
import com.cnsportiot.edge.calibration.CalibrationService.CalibrationRun;
import com.cnsportiot.edge.calibration.CalibrationService.CalibrationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/**
 * 球场标定(直播课)。产物按课程隔离在 {@code data/calibration/live_{课程id}/},
 * 上课拉起算法前由 {@code SessionService} 自动查一次;本控制器给前端手动查看与重标的入口
 */
@RestController
@RequestMapping("/local/calibration")
public class CalibrationController {

    private final CalibrationService calibrationService;

    public CalibrationController(CalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    /**
     * 查本课标定产物状态。只读文件系统,编排开关关着也能查
     *
     * @param session 课程 id
     */
    @GetMapping("/status")
    public ApiResponse<CalibrationStatus> status(@RequestParam String session) {
        return ApiResponse.ok(calibrationService.status(session));
    }

    /**
     * 触发标定(异步)。立即返回 RUNNING,前端轮询 {@link #status}。
     * 产物已齐且 {@code force=false} 时不重复执行,直接回上次结果
     */
    @PostMapping("/run")
    public ApiResponse<CalibrationRun> run(@Valid @RequestBody CalibrateRequest request) {
        return ApiResponse.ok(calibrationService.start(request.session(), request.force()));
    }

    /**
     * 触发标定(异步)
     *
     * @param session 课程 id(仅字母数字与 {@code _-},≤64)
     * @param force   true=产物已齐也重标;缺省 false
     */
    public record CalibrateRequest(
            @NotBlank @Size(max = 64) String session,
            boolean force) {
    }
}
