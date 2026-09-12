package com.cnsportiot.edge.rules;

import com.cnsportiot.edge.cloudsync.CloudIngestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 即时反馈上云缓冲:{@link RuleHit} 入队,定时批量按 sessionId 分组 POST 到云端 {@code /api/ingest/feedback}。
 * 与采集/评测链路解耦(走 cloudIoExecutor),断网时按 {@code maxBufferItems} 丢最旧保护内存。
 * eventId 幂等,重试/重复不重复入库。
 */
@Component
public class FeedbackForwarder {

    private static final Logger log = LoggerFactory.getLogger(FeedbackForwarder.class);

    private final CloudIngestClient cloud;
    private final CheckpointProperties props;
    private final TaskExecutor cloudIoExecutor;

    private final Queue<Buffered> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();

    public FeedbackForwarder(CloudIngestClient cloud, CheckpointProperties props,
                             @Qualifier("cloudIoExecutor") TaskExecutor cloudIoExecutor) {
        this.cloud = cloud;
        this.props = props;
        this.cloudIoExecutor = cloudIoExecutor;
    }

    public void enqueue(UUID sessionId, RuleHit hit) {
        while (size.get() >= props.getMaxBufferItems() && buffer.poll() != null) {
            size.decrementAndGet();   // 丢最旧
        }
        buffer.add(new Buffered(sessionId, hit));
        size.incrementAndGet();
    }

    @Scheduled(fixedDelayString = "${hoopshake.edge.rules.flush-interval-ms:2000}")
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        // 排空一批,按 sessionId 分组(null 单独一组,云端接受空 session)
        Map<UUID, List<Map<String, Object>>> bySession = new LinkedHashMap<>();
        Buffered b;
        while ((b = buffer.poll()) != null) {
            size.decrementAndGet();
            bySession.computeIfAbsent(b.sessionId(), k -> new ArrayList<>()).add(toItem(b.hit()));
        }
        bySession.forEach((sessionId, items) ->
                cloudIoExecutor.execute(() -> {
                    try {
                        cloud.pushFeedback(sessionId, items);
                    } catch (RuntimeException e) {
                        log.warn("即时反馈上云失败,丢弃本批 {} 条 sessionId={}: {}",
                                items.size(), sessionId, e.getMessage());
                    }
                }));
    }

    /** 转成云端 InstantFeedbackBatchRequest.Item 的 JSON 形状(字段名对齐)。 */
    private static Map<String, Object> toItem(RuleHit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", h.eventId());
        // 身份二选一:算法自管人脸→学号绑定时只有 studentNo,云端据此解析 studentId
        if (h.studentId() != null) {
            m.put("studentId", h.studentId().toString());
        } else if (h.studentNo() != null && !h.studentNo().isBlank()) {
            m.put("studentNo", h.studentNo());
        }
        m.put("occurredAt", h.occurredAt().toString());
        if (h.timestampMs() != null) {
            m.put("timestampMs", h.timestampMs());
        }
        m.put("actionType", h.actionType());
        m.put("checkpointId", h.checkpointId());
        m.put("severity", h.severity().name());
        m.put("cueText", h.cueText());
        m.put("measured", Map.of(h.metric(), h.value()));
        if (h.confidence() != null) {
            m.put("confidence", h.confidence());
        }
        if (h.sourceCamera() != null) {
            m.put("sourceCamera", h.sourceCamera());
        }
        return m;
    }

    private record Buffered(UUID sessionId, RuleHit hit) {
    }
}
