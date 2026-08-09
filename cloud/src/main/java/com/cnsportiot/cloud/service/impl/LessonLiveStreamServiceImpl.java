package com.cnsportiot.cloud.service.impl;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cnsportiot.cloud.dto.response.LessonDtos.LessonLiveResponse;
import com.cnsportiot.cloud.event.LessonLiveEvent;
import com.cnsportiot.cloud.service.LessonLiveStreamService;
import com.cnsportiot.cloud.service.LessonService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LessonLiveStreamServiceImpl implements LessonLiveStreamService {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    private final LessonService lessonService;

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lesson-live-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<SseEmitter, UUID> emitterLesson = new ConcurrentHashMap<>();
    private final ConcurrentMap<SseEmitter, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    @Override
    public SseEmitter stream(UUID lessonId, UUID loginTeacherId) {
        LessonLiveResponse snapshot = lessonService.getLive(lessonId, loginTeacherId);
        SseEmitter emitter = new SseEmitter(0L);
        registerEmitter(lessonId, emitter);
        sendEvent(emitter, "snapshot", snapshot);
        return emitter;
    }

    private void registerEmitter(UUID lessonId, SseEmitter emitter) {
        emitters.computeIfAbsent(lessonId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitterLesson.put(emitter, lessonId);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(throwable -> removeEmitter(emitter));

        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendEvent(emitter, "ping", "ping"),
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        heartbeatTasks.put(emitter, future);
    }

    private void removeEmitter(SseEmitter emitter) {
        ScheduledFuture<?> future = heartbeatTasks.remove(emitter);
        if (future != null) {
            future.cancel(true);
        }
        UUID lessonId = emitterLesson.remove(emitter);
        if (lessonId != null) {
            List<SseEmitter> list = emitters.get(lessonId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    emitters.remove(lessonId);
                }
            }
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException e) {
            removeEmitter(emitter);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLessonLiveEvent(LessonLiveEvent event) {
        List<SseEmitter> lessonEmitters = emitters.get(event.lessonId());
        if (lessonEmitters == null || lessonEmitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : lessonEmitters) {
            sendEvent(emitter, event.eventType(), event.payload());
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
    }
}
