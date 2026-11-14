package com.cnsportiot.edge.service.impl;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.cloudsync.CloudIngestClient;
import com.cnsportiot.edge.domain.RosterEntry;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.dto.RosterDtos.MatchResponse;
import com.cnsportiot.edge.dto.RosterDtos.RosterItem;
import com.cnsportiot.edge.dto.RosterDtos.RosterResponse;
import com.cnsportiot.edge.service.RosterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/** 名单缓存在内存中,配合选课流程使用:一次选课拉一次,课中只读 */
@Service
public class RosterServiceImpl implements RosterService {

    private static final Logger log = LoggerFactory.getLogger(RosterServiceImpl.class);

    private final CloudIngestClient cloudClient;
    private final IdentityBindingStore bindings;

    private volatile UUID lessonId;
    private volatile OffsetDateTime syncedAt;
    private volatile Map<String, RosterEntry> byStudentNo = Map.of();

    public RosterServiceImpl(CloudIngestClient cloudClient, IdentityBindingStore bindings) {
        this.cloudClient = cloudClient;
        this.bindings = bindings;
    }

    @Override
    public synchronized RosterResponse sync(UUID lessonId) {
        bindings.sessionBindings(lessonId.toString()).forEach((localId, binding) -> {
            try {
                cloudClient.syncFaceBinding(lessonId, localId,
                        bindings.globalIdForSessionLocal(lessonId.toString(), localId), binding.studentId(),
                        binding.studentNo(), null);
            } catch (RuntimeException e) {
                log.warn("历史人脸绑定补同步失败 lessonId={} studentNo={}: {}",
                        lessonId, binding.studentNo(), e.getMessage());
            }
        });

        List<RosterEntry> entries = cloudClient.fetchRoster(lessonId);

        Map<String, RosterEntry> map = new LinkedHashMap<>();
        entries.forEach(e -> map.put(e.studentNo(), e));

        this.byStudentNo = map;
        this.lessonId = lessonId;
        this.syncedAt = OffsetDateTime.now();

        long ready = byStudentNo.values().stream()
                .map(this::withLocalBinding)
                .filter(RosterEntry::galleryReady)
                .count();
        log.info("名单已同步 lessonId={} 共 {} 人,已采集特征 {} 人", lessonId, entries.size(), ready);

        // TODO 预留:此处按 gallery.storageUri 预拉 MinIO 特征文件到本地,供 CV 加载(E3)
        return toResponse();
    }

    @Override
    public RosterResponse current() {
        if (lessonId == null) {
            throw new BusinessException(EdgeErrorCode.ROSTER_NOT_LOADED);
        }
        return toResponse();
    }

    @Override
    public MatchResponse match(String studentNo) {
        if (lessonId == null) {
            throw new BusinessException(EdgeErrorCode.ROSTER_NOT_LOADED);
        }
        RosterEntry entry = byStudentNo.get(studentNo);
        return entry == null
                ? new MatchResponse(false, null)
                : new MatchResponse(true, toItem(entry));
    }

    @Override
    public Optional<RosterEntry> find(String studentNo) {
        if (studentNo == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byStudentNo.get(studentNo.trim())).map(this::withLocalBinding);
    }

    private RosterResponse toResponse() {
        List<RosterItem> items = byStudentNo.values().stream()
                .map(this::toItem)
                .toList();
        int ready = (int) items.stream().filter(RosterItem::galleryReady).count();
        return new RosterResponse(lessonId, syncedAt, items.size(), ready, items);
    }

    private RosterItem toItem(RosterEntry e) {
        RosterEntry effective = withLocalBinding(e);
        return new RosterItem(effective.studentId(), effective.studentNo(), effective.displayName(),
                effective.dominantHand(), effective.galleryReady());
    }

    private RosterEntry withLocalBinding(RosterEntry entry) {
        if (lessonId == null || entry.faceBound()
                || !bindings.isStudentNoBound(lessonId.toString(), entry.studentNo())) {
            return entry;
        }
        return new RosterEntry(entry.studentId(), entry.studentNo(), entry.displayName(),
                entry.dominantHand(), true, entry.galleryId(), entry.galleryVersion(),
                entry.galleryUri(), entry.faceModel(), entry.bodyModel());
    }
}
