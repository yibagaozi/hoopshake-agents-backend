package com.cnsportiot.cloud.service.impl;

import com.cnsportiot.cloud.domain.entity.*;
import com.cnsportiot.cloud.dto.request.IngestRequests.*;
import com.cnsportiot.cloud.dto.response.IngestDtos.*;
import com.cnsportiot.cloud.repository.*;
import com.cnsportiot.cloud.service.IngestService;
import com.cnsportiot.contracts.enums.GalleryStatus;
import com.cnsportiot.contracts.enums.SessionStatus;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IngestService {

    private final TrainingSessionRepository trainingSessionRepository;
    private final SessionOutputRawRepository sessionOutputRawRepository;
    private final IngestEventRepository ingestEventRepository;
    private final InstantFeedbackRepository instantFeedbackRepository;
    private final ReidGalleryRepository reidGalleryRepository;
    private final EdgeNodeRepository edgeNodeRepository;
    private final StudentRepository studentRepository;
    private final LessonEnrollmentRepository lessonEnrollmentRepository;

    @Override
    @Transactional
    public IngestAckResponse upsertSession(UUID sessionId, UpsertSessionRequest request) {
        var existing = trainingSessionRepository.findById(sessionId);

        if (existing.isPresent()) {
            TrainingSession session = existing.get();
            if (request.status().ordinal() <= session.getStatus().ordinal()) {
                return new IngestAckResponse(null, sessionId, false, true, session.getStatus());
            }
            session.setStatus(request.status());
            applySessionFields(session, request);
            trainingSessionRepository.save(session);
            log.info("会话状态更新 sessionId={} status={}", sessionId, request.status());
            return new IngestAckResponse(null, sessionId, true, false, request.status());
        }

        TrainingSession session = TrainingSession.builder()
                .lessonId(request.lessonId())
                .status(request.status())
                .coordinateSystem(request.coordinateSystem())
                .cameraConfigRef(request.cameraConfigRef())
                .dataDir(request.dataDir())
                .motionExportUri(request.motionExportUri())
                .recordedAt(request.recordedAt())
                .generatedAt(request.generatedAt())
                .metadata(request.metadata())
                .build();

        // 使用指定的 sessionId 作为主键
        try {
            var idField = com.cnsportiot.cloud.domain.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(session, sessionId);
        } catch (ReflectiveOperationException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法设置 sessionId");
        }

        trainingSessionRepository.save(session);
        log.info("会话已创建 sessionId={} lessonId={}", sessionId, request.lessonId());
        return new IngestAckResponse(null, sessionId, true, false, request.status());
    }

    @Override
    @Transactional
    public IngestAckResponse ingestSessionOutput(SessionOutputIngestRequest request) {
        if (sessionOutputRawRepository.existsByEventId(request.eventId())) {
            return new IngestAckResponse(request.eventId(), request.sessionId(), false, true, null);
        }

        recordEvent(request.eventId(), "session_output", request.producedAt());

        SessionOutputRaw raw = SessionOutputRaw.builder()
                .eventId(request.eventId())
                .sessionId(request.sessionId())
                .schemaVersion(request.schemaVersion())
                .producedAt(request.producedAt())
                .payload(request.payload())
                .build();

        try {
            sessionOutputRawRepository.save(raw);
        } catch (DataIntegrityViolationException e) {
            return new IngestAckResponse(request.eventId(), request.sessionId(), false, true, null);
        }

        log.info("原始输出已入库 eventId={} sessionId={}", request.eventId(), request.sessionId());
        return new IngestAckResponse(request.eventId(), request.sessionId(), true, false, null);
    }

    @Override
    @Transactional
    public BatchAckResponse ingestFeedback(InstantFeedbackBatchRequest request) {
        int accepted = 0;
        int duplicated = 0;
        List<RejectedItem> rejected = new ArrayList<>();

        for (var item : request.items()) {
            if (instantFeedbackRepository.existsByEventId(item.eventId())) {
                duplicated++;
                continue;
            }

            try {
                recordEvent(item.eventId(), "feedback", item.occurredAt());

                InstantFeedback fb = InstantFeedback.builder()
                        .eventId(item.eventId())
                        .sessionId(request.sessionId())
                        .studentId(item.studentId())
                        .occurredAt(item.occurredAt())
                        .timestampMs(item.timestampMs())
                        .actionType(item.actionType())
                        .checkpointId(item.checkpointId())
                        .severity(item.severity())
                        .cueText(item.cueText())
                        .measured(item.measured())
                        .confidence(item.confidence())
                        .sourceCamera(item.sourceCamera())
                        .build();
                instantFeedbackRepository.save(fb);
                accepted++;
            } catch (DataIntegrityViolationException e) {
                duplicated++;
            } catch (Exception e) {
                rejected.add(new RejectedItem(item.eventId(), e.getMessage()));
            }
        }

        log.info("反馈批量入库 accepted={} duplicated={} rejected={}", accepted, duplicated, rejected.size());
        return new BatchAckResponse(accepted, duplicated, rejected);
    }

    @Override
    @Transactional
    public GalleryRegisteredResponse registerGallery(RegisterGalleryRequest request) {
        // 将旧版 gallery 置为 SUPERSEDED
        reidGalleryRepository.findByStudentIdAndStatus(request.studentId(), GalleryStatus.ACTIVE)
                .ifPresent(old -> {
                    old.setStatus(GalleryStatus.SUPERSEDED);
                    reidGalleryRepository.save(old);
                });

        int version = request.version() != null ? request.version() : 1;

        ReidGallery gallery = ReidGallery.builder()
                .studentId(request.studentId())
                .version(version)
                .storageUri(request.storageUri())
                .faceModel(request.faceModel())
                .bodyModel(request.bodyModel())
                .faceDim(request.faceDim())
                .bodyDim(request.bodyDim())
                .sampleCount(request.sampleCount())
                .enrolledCamera(request.enrolledCamera())
                .enrolledAt(request.enrolledAt())
                .build();
        reidGalleryRepository.save(gallery);

        // 更新 student.activeGalleryId
        studentRepository.findById(request.studentId()).ifPresent(student -> {
            student.setActiveGalleryId(gallery.getId());
            studentRepository.save(student);
        });

        log.info("Gallery 已注册 studentId={} version={}", request.studentId(), version);
        return new GalleryRegisteredResponse(gallery.getId(), request.studentId(), version, gallery.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public GalleryPullResponse pullGallery(UUID lessonId) {
        if (lessonId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "lessonId 不能为空");
        }

        List<UUID> studentIds = lessonEnrollmentRepository.findStudentIdsByLessonId(lessonId);
        if (studentIds.isEmpty()) {
            return new GalleryPullResponse(lessonId, List.of());
        }

        var galleries = reidGalleryRepository.findByStudentIdInAndStatus(studentIds, GalleryStatus.ACTIVE);
        var galleryMap = new java.util.HashMap<UUID, ReidGallery>();
        galleries.forEach(g -> galleryMap.put(g.getStudentId(), g));

        List<GalleryPullStudent> students = new ArrayList<>();
        for (UUID studentId : studentIds) {
            var student = studentRepository.findById(studentId).orElse(null);
            if (student == null) continue;

            var account = new Object() { String displayName = null; };
            // displayName 来自 account 表,此处简化取 studentNo 代替
            GalleryRef ref = null;
            ReidGallery g = galleryMap.get(studentId);
            if (g != null) {
                ref = new GalleryRef(g.getId(), g.getVersion(), g.getStorageUri(), g.getFaceModel(), g.getBodyModel());
            }

            students.add(new GalleryPullStudent(
                    studentId, student.getStudentNo(), student.getStudentNo(),
                    student.getDominantHand(), ref));
        }

        return new GalleryPullResponse(lessonId, students);
    }

    @Override
    @Transactional
    public void heartbeat(EdgeHeartbeatRequest request) {
        EdgeNode node = edgeNodeRepository.findByEdgeId(request.edgeId())
                .orElseGet(() -> EdgeNode.builder().edgeId(request.edgeId()).lastHeartbeatAt(request.reportedAt()).build());

        node.setLastHeartbeatAt(request.reportedAt());
        node.setVersions(request.versions());
        if (request.cameras() != null) {
            node.setCameraCount(request.cameras().size());
            node.setOnlineCameras((int) request.cameras().stream().filter(CameraStatus::online).count());
        }
        edgeNodeRepository.save(node);
    }

    private void applySessionFields(TrainingSession session, UpsertSessionRequest request) {
        if (request.coordinateSystem() != null) session.setCoordinateSystem(request.coordinateSystem());
        if (request.cameraConfigRef() != null) session.setCameraConfigRef(request.cameraConfigRef());
        if (request.dataDir() != null) session.setDataDir(request.dataDir());
        if (request.motionExportUri() != null) session.setMotionExportUri(request.motionExportUri());
        if (request.recordedAt() != null) session.setRecordedAt(request.recordedAt());
        if (request.generatedAt() != null) session.setGeneratedAt(request.generatedAt());
        if (request.metadata() != null) session.setMetadata(request.metadata());
    }

    private void recordEvent(String eventId, String eventType, java.time.OffsetDateTime producedAt) {
        if (!ingestEventRepository.existsByEventId(eventId)) {
            try {
                ingestEventRepository.save(IngestEvent.builder()
                        .eventId(eventId).eventType(eventType).producedAt(producedAt).build());
            } catch (DataIntegrityViolationException ignored) {
                // 并发竞争,幂等
            }
        }
    }
}
