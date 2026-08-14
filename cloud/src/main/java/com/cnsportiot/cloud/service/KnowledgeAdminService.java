package com.cnsportiot.cloud.service;

import com.cnsportiot.cloud.dto.request.KnowledgeRequests.DebugSearchRequest;
import com.cnsportiot.cloud.dto.request.KnowledgeRequests.ImportDocumentRequest;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.DocumentResponse;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.ImportAcceptedResponse;
import com.cnsportiot.cloud.dto.response.KnowledgeDtos.SearchHitResponse;
import com.cnsportiot.contracts.common.PageResponse;

import java.util.List;
import java.util.UUID;

/** 知识库管理(运维/教研,ADMIN)。见 API §8、设计 §8.1~8.5。 */
public interface KnowledgeAdminService {

    /** §8.1 导入/登记(异步灌库)。 */
    ImportAcceptedResponse importDocument(ImportDocumentRequest request, UUID operatorAccountId);

    /** §8.2 列已导入源文档。 */
    PageResponse<DocumentResponse> list(int page, int size);

    /** §8.3 源文档详情。 */
    DocumentResponse get(String docId);

    /** §8.4 下架(删 chunk + 标 removed)。幂等。 */
    void delete(String docId, UUID operatorAccountId);

    /** §8.5 重切重灌(用当前参数/模型)。 */
    ImportAcceptedResponse reindex(String docId, UUID operatorAccountId);

    /** §8.6 调试检索。 */
    List<SearchHitResponse> debugSearch(DebugSearchRequest request);
}
