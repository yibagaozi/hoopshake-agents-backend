package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.LessonRequests.UpdateLessonRequest;
import com.cnsportiot.cloud.dto.request.LessonRequests.UpdateLessonStatusRequest;
import com.cnsportiot.cloud.dto.request.LessonRequests.CreateLessonRequest;
import com.cnsportiot.cloud.dto.response.LessonDtos.LessonResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.service.LessonService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 课程管理 */
@RestController
@RequestMapping("/api/teacher/lessons")
@RequireRole(Role.TEACHER)
public class LessonController {

    /** 1.4 约定的分页上限,超出即截断而非报错 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    /**
     * 5.1 创建课程
     *
     * <p>{@code actionTypes} 与 {@code enabledCheckpoints} 只校验码值存在于词表(§12),
     * 不校验二者相容性 —— 一期 CV 全量识别,这两个字段是教师侧的展示过滤器,
     * 交集为空不是错误。相容性提示由前端按词表联动灰置。
     */
    @PostMapping
    public ApiResponse<LessonResponse> create(
            @Valid @RequestBody CreateLessonRequest request,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(lessonService.create(request, me.accountId()));
    }

    /**
     * §5.2 课程分页列表
     *
     * <p>亦供场边操作台直连列课(§1.9):教师在场边浏览器内登录后拉本人课程,
     * 选定后把课程配置透传给 edge,edge 不经手教师 token、不回云二次查询。
     * 因此响应项必须完整带上 {@code actionTypes}/{@code enabledCheckpoints}。
     *
     * @param status 可多值,{@code ?status=PLANNED,ONGOING};省略则不按状态过滤
     */
    @GetMapping
    public ApiResponse<PageResponse<LessonResponse>> list(
            @RequestParam(required = false) List<LessonStatus> status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser AuthUser me) {

        Page<LessonResponse> result = lessonService.findTeacherLessons(
                me.accountId(), status, from, to,
                PageResponses.normalizePage(page), PageResponses.normalizeSize(size));

        return ApiResponse.ok(PageResponses.from(result));
    }

    /** §5.3 课程详情 */
    @GetMapping("/{lessonId}")
    public ApiResponse<LessonResponse> detail(
            @PathVariable UUID lessonId,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(lessonService.getDetail(lessonId, me.accountId()));
    }

    /**
     * §5.4 更新课程
     *
     * <p>字段均可选,{@code null} 即不改;仅 {@code PLANNED} 可改,否则 Service 抛 {@code 40910}。
     * 用 PUT 而非 PATCH 是沿用文档既定契约 —— 语义上是部分更新,前端按需传字段即可。
     */
    @PutMapping("/{lessonId}")
    public ApiResponse<LessonResponse> update(
            @PathVariable UUID lessonId,
            @Valid @RequestBody UpdateLessonRequest request,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(lessonService.update(lessonId, request, me.accountId()));
    }

    /**
     * §5.5 状态推进
     *
     * <p>仅 {@code PLANNED → ONGOING → FINISHED} 单向,跳跃或回退由 Service 抛 {@code 40910}。
     * 用 POST 子资源而非 PUT 整体更新:状态推进是有副作用的动作(会影响 §5.7 报名、§8.1 汇总),
     * 不该和字段更新走同一个入口。
     */
    @PostMapping("/{lessonId}/status")
    public ApiResponse<LessonResponse> updateStatus(
            @PathVariable UUID lessonId,
            @Valid @RequestBody UpdateLessonStatusRequest request,
            @CurrentUser AuthUser me) {

        return ApiResponse.ok(lessonService.updateStatus(lessonId, request, me.accountId()));
    }

}

