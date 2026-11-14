package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.service.LessonContextService;
import com.cnsportiot.edge.dto.LessonDtos.LessonContextResponse;
import com.cnsportiot.edge.dto.LessonDtos.LessonSelectResponse;
import com.cnsportiot.edge.dto.LessonDtos.SelectLessonRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上课前选课(Cloud API §1.9)
 *
 * <p>教师在操作台浏览器内直连云端登录与列课,选定后把 lessonId 与课程配置交给本接口。
 * 选课会同步拉取参课名单,完成后会话进入 READY。
 */
@RestController
@RequestMapping("/local/lesson")
public class LessonController {

    private final LessonContextService lessonContextService;

    public LessonController(LessonContextService lessonContextService) {
        this.lessonContextService = lessonContextService;
    }

    /** 选课并拉取名单 */
    @PostMapping("/select")
    public ApiResponse<LessonSelectResponse> select(@Valid @RequestBody SelectLessonRequest request) {
        return ApiResponse.ok(lessonContextService.select(request));
    }

    /** 当前选定课程;未选课时各字段为空 */
    @GetMapping
    public ApiResponse<LessonContextResponse> current() {
        return ApiResponse.ok(lessonContextService.current());
    }

    /** 清空选课,用于换课 */
    @DeleteMapping
    public ApiResponse<Void> clear() {
        lessonContextService.clear();
        return ApiResponse.ok();
    }
}

