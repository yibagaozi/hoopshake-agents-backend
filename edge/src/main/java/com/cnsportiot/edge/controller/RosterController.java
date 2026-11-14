package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.service.RosterService;
import com.cnsportiot.edge.dto.RosterDtos.MatchRequest;
import com.cnsportiot.edge.dto.RosterDtos.MatchResponse;
import com.cnsportiot.edge.dto.RosterDtos.RosterResponse;
import com.cnsportiot.edge.dto.RosterDtos.SyncRosterRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 参课名单:从云端拉取学生信息并缓存(E3) */
@RestController
@RequestMapping("/local/roster")
public class RosterController {

    private final RosterService rosterService;

    public RosterController(RosterService rosterService) {
        this.rosterService = rosterService;
    }

    /** 选课后拉取名单与 gallery 引用 */
    @PostMapping("/sync")
    public ApiResponse<RosterResponse> sync(@Valid @RequestBody SyncRosterRequest request) {
        return ApiResponse.ok(rosterService.sync(UUID.fromString(request.lessonId())));
    }

    /** 读本地缓存的名单,断网可用 */
    @GetMapping
    public ApiResponse<RosterResponse> current() {
        return ApiResponse.ok(rosterService.current());
    }

    /** 现场注册:按完整学号精确匹配 */
    @PostMapping("/match")
    public ApiResponse<MatchResponse> match(@Valid @RequestBody MatchRequest request) {
        return ApiResponse.ok(rosterService.match(request.studentNo()));
    }
}
