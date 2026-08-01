package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.Lesson;
import com.cnsportiot.cloud.domain.enums.LessonStatus;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests.ImportEnrollmentRequest;
import com.cnsportiot.cloud.dto.request.EnrollmentRequests.StudentEntry;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.EnrollmentItem;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.ImportPreviewResponse;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.InvalidItem;
import com.cnsportiot.cloud.dto.response.EnrollmentDtos.PreviewItem;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository;
import com.cnsportiot.cloud.repository.LessonEnrollmentRepository.EnrollmentView;
import com.cnsportiot.cloud.repository.LessonRepository;
import com.cnsportiot.cloud.repository.StudentRepository;
import com.cnsportiot.cloud.repository.StudentRepository.StudentRef;
import com.cnsportiot.cloud.service.LessonEnrollmentService;
import com.cnsportiot.cloud.service.StudentProvisioningService;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LessonEnrollmentServiceImpl implements LessonEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(LessonEnrollmentServiceImpl.class);

    private static final Pattern STUDENT_NO = Pattern.compile("^\\d{10}$");
    private static final String INVALID_REASON = "学号须为 10 位数字";

    private final LessonRepository lessonRepository;
    private final StudentRepository studentRepository;
    private final LessonEnrollmentRepository enrollmentRepository;
    private final StudentProvisioningService studentProvisioning;

    @Override
    public List<EnrollmentItem> list(UUID lessonId, UUID loginTeacherId) {
        requireOwnedLesson(lessonId, loginTeacherId);

        return toItems(lessonId, Set.of());
    }

    // 5.7 批量导入
    // 整个导入一个事务:中途失败时已建的账号跟着回滚
    @Override
    @Transactional
    public List<EnrollmentItem> importStudents(UUID lessonId,
                                               ImportEnrollmentRequest request,
                                               UUID loginTeacherId) {
        Lesson lesson = requireOwnedLesson(lessonId, loginTeacherId);
        requireNotFinished(lesson);

        Map<String, StudentEntry> deduped = dedupe(request.students());
        Map<String, UUID> existing = findExistingIds(deduped.keySet());

        Set<UUID> justCreated = new java.util.LinkedHashSet<>();
        int newlyEnrolled = 0;

        for (StudentEntry entry : deduped.values()) {
            UUID studentId = existing.get(entry.studentNo());
            if (studentId == null) {
                studentId = studentProvisioning.provisionByStudentNo(
                        entry.studentNo(), entry.displayName());
                justCreated.add(studentId);
            }
            // 幂等交给 DB 的唯一约束,返回 1 表示本次新增
            newlyEnrolled += enrollmentRepository.enrollIfAbsent(lessonId, studentId);
        }

        log.info("名单导入 lessonId={} 提交 {} 条(去重后),新建学生 {} 人,新增报名 {} 人",
                lessonId, deduped.size(), justCreated.size(), newlyEnrolled);

        return toItems(lessonId, justCreated);
    }

    // 5.11 预检
    // dry-run,不写任何库
    @Override
    public ImportPreviewResponse preview(UUID lessonId,
                                         ImportEnrollmentRequest request,
                                         UUID loginTeacherId) {
        requireOwnedLesson(lessonId, loginTeacherId);

        Map<String, StudentEntry> deduped = dedupe(request.students());

        List<InvalidItem> invalid = new ArrayList<>();
        List<String> wellFormed = new ArrayList<>();
        for (String studentNo : deduped.keySet()) {
            if (studentNo == null || !STUDENT_NO.matcher(studentNo).matches()) {
                invalid.add(new InvalidItem(studentNo, INVALID_REASON));
            } else {
                wellFormed.add(studentNo);
            }
        }

        Map<String, StudentRef> existing = studentRepository.findRefsByStudentNoIn(wellFormed)
                .stream()
                .collect(Collectors.toMap(StudentRef::getStudentNo, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Set<UUID> enrolled = Set.copyOf(enrollmentRepository.findStudentIdsByLessonId(lessonId));

        List<PreviewItem> willCreate = new ArrayList<>();
        List<PreviewItem> willEnroll = new ArrayList<>();
        List<PreviewItem> alreadyEnrolled = new ArrayList<>();

        for (String studentNo : wellFormed) {
            StudentRef ref = existing.get(studentNo);
            if (ref == null) {
                // 尚不存在:displayName 取教师填的,建档要用
                willCreate.add(PreviewItem.toCreate(studentNo, deduped.get(studentNo).displayName()));
            } else {
                // 已存在:displayName 取库里的,让教师看清要绑的到底是谁,
                // 而不是回显他刚敲进去、可能敲错的那个名字
                PreviewItem item = new PreviewItem(studentNo, ref.getDisplayName(), ref.getStudentId());
                (enrolled.contains(ref.getStudentId()) ? alreadyEnrolled : willEnroll).add(item);
            }
        }

        return new ImportPreviewResponse(willCreate, willEnroll, alreadyEnrolled, invalid);
    }

    // 5.8 取消报名
    @Override
    @Transactional
    public void cancel(UUID lessonId, UUID studentId, UUID loginTeacherId) {
        Lesson lesson = requireOwnedLesson(lessonId, loginTeacherId);
        requireNotFinished(lesson);

        int removed = enrollmentRepository.deleteByLessonIdAndStudentId(lessonId, studentId);
        if (removed == 0) {
            log.debug("取消报名无命中(幂等) lessonId={} studentId={}", lessonId, studentId);
        }
    }

    // 按学号去重,保留首次出现的那条
    private static Map<String, StudentEntry> dedupe(List<StudentEntry> students) {
        Map<String, StudentEntry> map = new LinkedHashMap<>();
        for (StudentEntry entry : students) {
            map.putIfAbsent(entry.studentNo(), entry);
        }
        return map;
    }

    private Map<String, UUID> findExistingIds(java.util.Collection<String> studentNos) {
        return studentRepository.findRefsByStudentNoIn(List.copyOf(studentNos)).stream()
                .collect(Collectors.toMap(StudentRef::getStudentNo, StudentRef::getStudentId,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private List<EnrollmentItem> toItems(UUID lessonId, Set<UUID> justCreated) {
        List<EnrollmentView> views = enrollmentRepository.findEnrollmentView(lessonId);
        List<EnrollmentItem> items = new ArrayList<>(views.size());
        for (EnrollmentView v : views) {
            items.add(new EnrollmentItem(
                    v.getStudentId(),
                    v.getStudentNo(),
                    v.getDisplayName(),
                    v.getAccountStatus(),
                    v.getGalleryReady(),
                    justCreated.contains(v.getStudentId()),
                    v.getEnrolledAt()));
        }
        return items;
    }

    // 归属校验
    private Lesson requireOwnedLesson(UUID lessonId, UUID loginTeacherId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> BusinessException.notFound("课程不存在"));
        if (!lesson.getTeacherId().equals(loginTeacherId)) {
            throw BusinessException.dataScopeDenied();
        }
        return lesson;
    }

    // 已结课的名单不再变动
    private static void requireNotFinished(Lesson lesson) {
        if (lesson.getStatus() == LessonStatus.FINISHED) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "课程已结束,名单不可变更");
        }
    }
}

