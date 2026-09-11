package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.domain.entity.ChatSession;
import com.cnsportiot.cloud.domain.entity.HelpRequest;
import com.cnsportiot.cloud.domain.enums.HelpRequestStatus;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.CreateHelpRequest;
import com.cnsportiot.cloud.dto.request.HelpRequestRequests.HandleHelpRequest;
import com.cnsportiot.cloud.dto.response.HelpRequestDtos.HelpRequestResponse;
import com.cnsportiot.cloud.dto.response.HelpRequestDtos.TeacherHelpRequestItem;
import com.cnsportiot.cloud.repository.ChatSessionRepository;
import com.cnsportiot.cloud.repository.HelpRequestRepository;
import com.cnsportiot.cloud.repository.HelpRequestRepository.HelpRequestView;
import com.cnsportiot.cloud.service.HelpRequestService;
import com.cnsportiot.contracts.common.PageResponse;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HelpRequestServiceImpl implements HelpRequestService {

    private final HelpRequestRepository helpRepo;
    private final ChatSessionRepository sessionRepo;

    @Override
    @Transactional
    public HelpRequestResponse create(UUID studentId, CreateHelpRequest request) {
        requireStudent(studentId);

        // 若给了会话,校验归属并借它带出 lessonId
        UUID lessonId = null;
        if (request.sessionId() != null) {
            ChatSession session = sessionRepo.findById(request.sessionId()).orElse(null);
            if (session != null && studentId.equals(session.getStudentId())) {
                lessonId = session.getLessonId();
            }
        }

        Map<String, Object> context = (request.contextNote() == null || request.contextNote().isBlank())
                ? null : Map.of("note", request.contextNote().strip());

        HelpRequest hr = HelpRequest.builder()
                .studentId(studentId)
                .lessonId(lessonId)
                .sessionId(request.sessionId())
                .question(request.question().strip())
                .context(context)
                .status(HelpRequestStatus.PENDING)
                .build();
        hr = helpRepo.save(hr);
        return toStudentDto(hr);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HelpRequestResponse> listForStudent(UUID studentId, int page, int size) {
        requireStudent(studentId);
        return PageResponses.from(
                helpRepo.findByStudentIdOrderByCreatedAtDesc(studentId, PageResponses.toPageable(page, size)),
                this::toStudentDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TeacherHelpRequestItem> listForTeacher(
            UUID teacherAccountId, HelpRequestStatus status, int page, int size) {
        var pageable = PageResponses.toPageable(page, size);
        // status 为 null 时仓储侧不过滤(合并查询,见 HelpRequestRepository.findForTeacher)
        Page<HelpRequestView> result = helpRepo.findForTeacher(teacherAccountId, status, pageable);
        return PageResponses.from(result, this::toTeacherItem);
    }

    @Override
    @Transactional
    public TeacherHelpRequestItem handle(UUID teacherAccountId, UUID requestId, HandleHelpRequest request) {
        HelpRequest hr = helpRepo.findById(requestId)
                .orElseThrow(() -> BusinessException.notFound("协助工单不存在"));
        if (!helpRepo.isStudentOfTeacher(teacherAccountId, hr.getStudentId())) {
            throw BusinessException.dataScopeDenied();   // 该生不在本教师名下
        }
        hr.setStatus(request.status());
        if (request.reply() != null && !request.reply().isBlank()) {
            hr.setTeacherReply(request.reply().strip());
        }
        if (request.status() == HelpRequestStatus.RESOLVED || request.status() == HelpRequestStatus.DISMISSED) {
            hr.setHandledBy(teacherAccountId);
            hr.setHandledAt(OffsetDateTime.now());
        }
        helpRepo.save(hr);
        return toTeacherItem(helpRepo.findViewById(hr.getId())
                .orElseThrow(() -> BusinessException.notFound("协助工单不存在")));
    }

    // ---- helpers ----

    private void requireStudent(UUID studentId) {
        if (studentId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前账号无 studentId(非学生身份),无法使用学生协助端点。");
        }
    }

    private HelpRequestResponse toStudentDto(HelpRequest hr) {
        return new HelpRequestResponse(hr.getId(), hr.getSessionId(), hr.getQuestion(),
                hr.getStatus(), hr.getTeacherReply(), hr.getCreatedAt(), hr.getHandledAt());
    }

    private TeacherHelpRequestItem toTeacherItem(HelpRequestView v) {
        return new TeacherHelpRequestItem(v.getId(), v.getStudentId(), v.getStudentNo(), v.getDisplayName(),
                v.getLessonId(), v.getSessionId(), v.getQuestion(), v.getStatus(),
                v.getTeacherReply(), v.getCreatedAt(), v.getHandledAt());
    }
}

