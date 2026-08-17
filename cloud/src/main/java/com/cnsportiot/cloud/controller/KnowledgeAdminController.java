package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.annotation.CurrentUser;
import com.cnsportiot.cloud.annotation.RequireRole;
import com.cnsportiot.cloud.domain.enums.Role;
import com.cnsportiot.cloud.dto.request.KnowledgeRequests.DebugSearchRequest;
import com.cnsportiot.cloud.dto.request.KnowledgeRequests.ImportDocumentRequest;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.DocumentResponse;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.ImportAcceptedResponse;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.SearchHitResponse;
import com.cnsportiot.cloud.security.AuthUser;
import com.cnsportiot.cloud.service.KnowledgeAdminService;
import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库管理(运维/教研)
 * 导入为异步:返回 docId + 初始状态,轮询 {@code GET /documents/{docId}} 看 status 变 DONE/FAILED。
 */
@RestController
@RequestMapping("/api/admin/knowledge")
@RequireRole(Role.ADMIN)
public class KnowledgeAdminController {

    private final KnowledgeAdminService knowledgeAdminService;

    public KnowledgeAdminController(KnowledgeAdminService knowledgeAdminService) {
        this.knowledgeAdminService = knowledgeAdminService;
    }

    /** 8.1 导入/登记(异步灌库) */
    @PostMapping("/documents")
    public ApiResponse<ImportAcceptedResponse> importDocument(
            @Valid @RequestBody ImportDocumentRequest request,
            @CurrentUser AuthUser me) {
        return ApiResponse.ok(knowledgeAdminService.importDocument(request, me.accountId()));
    }

    /** 8.2 列已导入源文档 */
    @GetMapping("/documents")
    public ApiResponse<PageResponse<DocumentResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(knowledgeAdminService.list(page, size));
    }

    /** 8.3 源文档详情(含导入 status,兼作导入进度轮询) */
    @GetMapping("/documents/{docId}")
    public ApiResponse<DocumentResponse> get(@PathVariable String docId) {
        return ApiResponse.ok(knowledgeAdminService.get(docId));
    }

    /** 8.4 下架。幂等 */
    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Void> delete(@PathVariable String docId, @CurrentUser AuthUser me) {
        knowledgeAdminService.delete(docId, me.accountId());
        return ApiResponse.ok();
    }

    /** 8.5 重切重灌 */
    @PostMapping("/documents/{docId}/reindex")
    public ApiResponse<ImportAcceptedResponse> reindex(@PathVariable String docId, @CurrentUser AuthUser me) {
        return ApiResponse.ok(knowledgeAdminService.reindex(docId, me.accountId()));
    }

    /** 8.6 调试检索:验证"这条知识能不能被召回" */
    @PostMapping("/search")
    public ApiResponse<List<SearchHitResponse>> search(@Valid @RequestBody DebugSearchRequest request) {
        return ApiResponse.ok(knowledgeAdminService.debugSearch(request));
    }
}

