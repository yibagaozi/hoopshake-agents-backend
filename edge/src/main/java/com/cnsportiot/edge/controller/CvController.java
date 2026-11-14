package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.cv.CvProcessManager;
import com.cnsportiot.edge.cv.CvProcessManager.CvStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CV(算法推理进程)手动管控。CV 不再随 edge 启动自拉起——上课时按 session 自动拉起,
 * 此处提供操作台“启动/停止/重启算法”按钮与状态查询,便于无课场景测试或排障。
 */
@RestController
@RequestMapping("/local/cv")
public class CvController {

    private final CvProcessManager cv;

    public CvController(CvProcessManager cv) {
        this.cv = cv;
    }

    /**
     * 启动算法进程。{@code session} 可选:给了就用它(需与注册时的 session 一致才能认脸),
     * 不给用 {@code cv.default-session}。已在跑同一 session 则幂等;不同 session 则切换重启。
     */
    @PostMapping("/start")
    public ApiResponse<CvStatus> start(@RequestParam(required = false) String session) {
        cv.start(session);
        return ApiResponse.ok(cv.status());
    }

    /** 停止算法进程。 */
    @PostMapping("/stop")
    public ApiResponse<CvStatus> stop() {
        cv.stop();
        return ApiResponse.ok(cv.status());
    }

    /** 以当前 session 重启算法进程。 */
    @PostMapping("/restart")
    public ApiResponse<CvStatus> restart() {
        cv.restart();
        return ApiResponse.ok(cv.status());
    }

    /** 算法进程状态(state + 当前 session)。 */
    @GetMapping("/status")
    public ApiResponse<CvStatus> status() {
        return ApiResponse.ok(cv.status());
    }
}
