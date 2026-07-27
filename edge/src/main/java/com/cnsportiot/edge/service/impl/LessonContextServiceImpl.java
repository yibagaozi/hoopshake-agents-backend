package com.cnsportiot.edge.service.impl;

import com.cnsportiot.edge.service.LessonContextService;
import com.cnsportiot.edge.service.RosterService;
import com.cnsportiot.edge.domain.LessonContext;
import com.cnsportiot.edge.dto.LessonDtos.LessonContextResponse;
import com.cnsportiot.edge.dto.LessonDtos.LessonSelectResponse;
import com.cnsportiot.edge.dto.LessonDtos.SelectLessonRequest;
import com.cnsportiot.edge.dto.RosterDtos.RosterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LessonContextServiceImpl implements LessonContextService {

    private static final Logger log = LoggerFactory.getLogger(LessonContextServiceImpl.class);

    private final RosterService rosterService;
    private final AtomicReference<LessonContext> current = new AtomicReference<>();

    public LessonContextServiceImpl(RosterService rosterService) {
        this.rosterService = rosterService;
    }

    @Override
    public synchronized LessonSelectResponse select(SelectLessonRequest request) {
        UUID lessonId = UUID.fromString(request.lessonId());

        LessonContext ctx = new LessonContext(
                lessonId,
                request.title(),
                request.classCode(),
                request.actionTypes() == null ? List.of() : List.copyOf(request.actionTypes()),
                request.enabledCheckpoints() == null ? List.of() : List.copyOf(request.enabledCheckpoints()),
                request.zoneConfigRef(),
                OffsetDateTime.now());

        // 选课即拉名单;拉取失败直接抛出,不保留半就绪的上下文
        RosterResponse roster = rosterService.sync(lessonId);
        current.set(ctx);

        log.info("已选课 lessonId={} title={} 名单 {} 人(特征就绪 {} 人)",
                lessonId, request.title(), roster.total(), roster.galleryReadyCount());

        return new LessonSelectResponse(
                ctx.lessonId(), ctx.title(), ctx.classCode(),
                ctx.actionTypes(), ctx.enabledCheckpoints(), ctx.zoneConfigRef(),
                ctx.selectedAt(), roster.total(), roster.galleryReadyCount());
    }

    @Override
    public LessonContextResponse current() {
        LessonContext ctx = current.get();
        if (ctx == null) {
            return LessonContextResponse.empty();
        }
        return new LessonContextResponse(
                ctx.lessonId(), ctx.title(), ctx.classCode(),
                ctx.actionTypes(), ctx.enabledCheckpoints(),
                ctx.zoneConfigRef(), ctx.selectedAt());
    }

    @Override
    public Optional<LessonContext> context() {
        return Optional.ofNullable(current.get());
    }

    @Override
    public void clear() {
        current.set(null);
    }
}

