package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.common.PageResponses;
import com.cnsportiot.cloud.config.StudentProperties;
import com.cnsportiot.cloud.domain.entity.Account;
import com.cnsportiot.cloud.domain.entity.AuditLog;
import com.cnsportiot.cloud.domain.entity.Student;
import com.cnsportiot.cloud.domain.enums.AccountStatus;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.RegisterStudentRequest;
import com.cnsportiot.cloud.dto.request.StudentManageRequests.UpdateStudentRequest;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.RegisterStudentResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentBriefResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentDetailResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentStatsResponse;
import com.cnsportiot.cloud.dto.response.StudentManageDtos.StudentActionStat;
import com.cnsportiot.cloud.repository.AccountRepository;
import com.cnsportiot.cloud.repository.AuditLogRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.repository.ActionClipRepository;
import com.cnsportiot.cloud.repository.TrainingSessionRepository;
import com.cnsportiot.cloud.service.StudentManageService;
import com.cnsportiot.contracts.common.PageResponse;
import com.cnsportiot.contracts.error.BusinessException;
import java.util.List;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 学生管理实现 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentManageServiceImpl implements StudentManageService {

    private final AccountRepository accountRepository;
    private final StudentRepository studentRepository;
    private final ActionClipRepository actionClipRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final StudentProperties studentProperties;

    @Override
    @Transactional
    public RegisterStudentResponse registerStudent(RegisterStudentRequest request, String operatorAccountId) {
        if (studentRepository.existsByStudentNo(request.studentNo())
                || accountRepository.existsByUsername(request.username() == null ? request.studentNo() : request.username())
                || (request.email() != null && accountRepository.existsByEmail(request.email()))
                || (request.phone() != null && accountRepository.existsByPhone(request.phone()))) {
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER,
                    "学号、用户名、邮箱或手机号已存在");
        }

        try {
            String username = request.username() == null ? request.studentNo() : request.username();
            String initialPassword = request.password() == null
                    ? studentProperties.initialPasswordFor(request.studentNo())
                    : request.password();
            String encodedPassword = passwordEncoder.encode(initialPassword);

            Account account = Account.builder()
                    .username(username)
                    .email(request.email())
                    .phone(request.phone())
                    .displayName(request.displayName())
                    .passwordHash(encodedPassword)
                    .role(com.cnsportiot.cloud.domain.enums.Role.STUDENT)
                    .status(AccountStatus.PENDING_ACTIVATION)
                    .build();
            Account savedAccount = accountRepository.save(account);

            Student student = Student.builder()
                    .accountId(savedAccount.getId())
                    .studentNo(request.studentNo())
                    .dominantHand(request.dominantHand())
                    .heightCm(request.heightCm())
                    .legLengthCm(request.legLengthCm())
                    .gradeBand(request.gradeBand())
                    .build();
            studentRepository.save(student);

            auditLogRepository.save(AuditLog.builder()
                    .accountId(UUID.fromString(operatorAccountId))
                    .action("STUDENT_CREATE")
                    .targetStudentId(student.getId())
                    .build());

            log.info("学生建档 studentNo={} username={} studentId={} operator={}",
                    request.studentNo(), username, student.getId(), operatorAccountId);

            return new RegisterStudentResponse(
                    student.getId(), savedAccount.getId(), request.studentNo(), username,
                    request.password() == null);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_IDENTIFIER,
                    "学号、用户名、邮箱或手机号已存在", null);
        }
    }

    @Override
    public PageResponse<StudentBriefResponse> listStudents(String keyword, int page, int size) {
        PageRequest pageable = PageRequest.of(PageResponses.normalizePage(page), PageResponses.normalizeSize(size));
        Page<StudentRepository.StudentBrief> result = studentRepository.findBriefsByKeyword(keyword, pageable);
        return PageResponses.from(result, record -> new StudentBriefResponse(
                record.getStudentId(),
                record.getStudentNo(),
                record.getDisplayName(),
                record.getGradeBand(),
                record.getDominantHand(),
                record.isGalleryReady()));
    }

    @Override
    public StudentDetailResponse getStudentDetail(UUID studentId) {
        StudentRepository.StudentDetail detail = studentRepository.findActiveDetailByStudentId(studentId)
                .orElseThrow(() -> BusinessException.notFound("学生不存在或未激活"));
        return new StudentDetailResponse(
                detail.getStudentId(),
                detail.getStudentNo(),
                detail.getDisplayName(),
                detail.getDominantHand(),
                detail.getHeightCm(),
                detail.getLegLengthCm(),
                detail.getGradeBand(),
                detail.getGalleryId() == null ? null : new com.cnsportiot.cloud.dto.response.StudentManageDtos.ActiveGallerySummary(
                        detail.getGalleryId(),
                        detail.getGalleryVersion() == null ? 0 : detail.getGalleryVersion().intValue(),
                        com.cnsportiot.contracts.enums.GalleryStatus.valueOf(detail.getGalleryStatus()),
                        detail.getGallerySampleCount(),
                        detail.getGalleryEnrolledAt()));
    }

    @Override
    @Transactional
    public StudentDetailResponse updateStudent(UUID studentId, UpdateStudentRequest request) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> BusinessException.notFound("学生不存在"));

        if (request.displayName() != null) {
            accountRepository.findById(student.getAccountId())
                    .ifPresent(account -> account.setDisplayName(request.displayName()));
        }
        if (request.dominantHand() != null) {
            student.setDominantHand(request.dominantHand());
        }
        if (request.heightCm() != null) {
            student.setHeightCm(request.heightCm());
        }
        if (request.legLengthCm() != null) {
            student.setLegLengthCm(request.legLengthCm());
        }
        if (request.gradeBand() != null) {
            student.setGradeBand(request.gradeBand());
        }

        studentRepository.save(student);
        accountRepository.flush();
        studentRepository.flush();

        return getStudentDetail(studentId);
    }

    @Override
    public StudentStatsResponse getStudentStats(UUID studentId) {
        if (studentRepository.findById(studentId).isEmpty()) {
            throw BusinessException.notFound("学生不存在");
        }

        List<StudentActionStat> byAction = actionClipRepository.findActionStatsByStudentId(studentId)
                .stream()
                .map(r -> new StudentActionStat(r.getActionType(), r.getClipCount(), r.getMadeRate(), r.getLastAt()))
                .toList();

        long totalClips = actionClipRepository.countByStudentId(studentId);
        long totalSessions = actionClipRepository.countDistinctSessionByStudentId(studentId);
        java.time.OffsetDateTime lastSessionAt = trainingSessionRepository.findLastRecordedAtByStudentId(studentId)
                .orElse(null);

        return new StudentStatsResponse(studentId,
                studentRepository.findById(studentId)
                        .flatMap(s -> accountRepository.findById(s.getAccountId()))
                        .map(com.cnsportiot.cloud.domain.entity.Account::getDisplayName)
                        .orElse(null),
                totalSessions,
                totalClips,
                lastSessionAt,
                byAction);
    }
}
