package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话结束出云:读算法交接文件 {@code {session}/cloud/ingest.json} → 上传逐帧 motion / gallery 特征到对象存储 →
 * 回填 uri → 调 {@link CloudIngestClient} 的 upsertSession / pushActionClips / registerGallery。
 *
 * <p>整体尽力而为:每一步独立 try/catch,单步失败(网络/磁盘)只记日志不中断其余步骤,逐帧文件可回头补传。
 * 同步方法便于测试;由 {@link SessionPublishListener} 交 cloudIoExecutor 异步执行。
 */
@Component
public class SessionCloudPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionCloudPublisher.class);
    private static final String META_FILE = "meta.json";

    private final EdgeProperties props;
    private final ObjectStore objectStore;
    private final CloudIngestClient cloud;
    private final ObjectMapper objectMapper;
    /** 批处理出云 MQ 生产者;未启用 MQ 时为 null → 回退 HTTP。 */
    private final MqIngestPublisher mq;

    public SessionCloudPublisher(EdgeProperties props, ObjectStore objectStore, CloudIngestClient cloud,
                                 ObjectMapper objectMapper, ObjectProvider<MqIngestPublisher> mqProvider) {
        this.props = props;
        this.objectStore = objectStore;
        this.cloud = cloud;
        this.objectMapper = objectMapper;
        this.mq = mqProvider.getIfAvailable();
    }

    public void publish(UUID sessionId) {
        if (!props.getPublish().isEnabled()) {
            return;
        }
        Path dir = Path.of(props.getDataRoot(), "sessions", sessionId.toString());
        Path handoffPath = dir.resolve(props.getPublish().getHandoffRelPath());
        if (!Files.exists(handoffPath)) {
            log.warn("交接文件不存在,跳过出云 session={} path={}", sessionId, handoffPath);
            return;
        }
        SessionHandoff handoff;
        try {
            handoff = objectMapper.readValue(Files.readString(handoffPath), SessionHandoff.class);
        } catch (Exception e) {
            log.error("交接文件解析失败,放弃出云 session={}: {}", sessionId, e.getMessage());
            return;
        }

        String motionUri = uploadMotion(sessionId, dir, handoff);
        upsertSession(sessionId, dir, handoff, motionUri);
        pushClips(sessionId, handoff, motionUri);
        pushGalleries(sessionId, dir, handoff);
        log.info("会话出云完成 session={} transport={} clips={} galleries={}", sessionId,
                mq != null ? "mq" : "http",
                handoff.clips() == null ? 0 : handoff.clips().size(),
                handoff.galleries() == null ? 0 : handoff.galleries().size());
    }

    private String uploadMotion(UUID sessionId, Path dir, SessionHandoff h) {
        if (h.session() == null || h.session().motionExportRelPath() == null) {
            return null;
        }
        Path motion = dir.resolve(h.session().motionExportRelPath());
        if (!Files.exists(motion)) {
            log.warn("motion 文件缺失,motion_uri 留空 session={} path={}", sessionId, motion);
            return null;
        }
        try {
            String key = "sessions/" + sessionId + "/" + motion.getFileName();
            return objectStore.put(key, motion, "application/x-ndjson").uri();
        } catch (RuntimeException e) {
            log.warn("motion 上传失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void upsertSession(UUID sessionId, Path dir, SessionHandoff h, String motionUri) {
        try {
            SessionHandoff.SessionPart sp = h.session();
            Map<String, Object> body = new LinkedHashMap<>();
            UUID lessonId = sp != null && sp.lessonId() != null ? sp.lessonId() : readLessonId(dir);
            if (lessonId != null) {
                body.put("lessonId", lessonId.toString());
            }
            body.put("status", sp != null && sp.status() != null ? sp.status() : "SCORED");
            if (sp != null) {
                if (sp.coordinateSystem() != null) {
                    body.put("coordinateSystem", sp.coordinateSystem());
                }
                if (sp.cameraConfigRef() != null) {
                    body.put("cameraConfigRef", sp.cameraConfigRef());
                }
                if (sp.generatedAt() != null) {
                    body.put("generatedAt", sp.generatedAt());
                }
            }
            body.put("dataDir", dir.toString());
            if (motionUri != null) {
                body.put("motionExportUri", motionUri);
            }
            if (mq != null) {
                mq.sendSession(sessionId, body);
            } else {
                cloud.reportSession(sessionId, body);
            }
        } catch (RuntimeException e) {
            log.warn("会话状态上报失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private void pushClips(UUID sessionId, SessionHandoff h, String motionUri) {
        if (h.clips() == null || h.clips().isEmpty()) {
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionHandoff.ClipPart c : h.clips()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", c.studentId() == null ? null : c.studentId().toString());
            m.put("clipIndex", c.clipIndex());
            m.put("actionType", c.actionType());
            m.put("startMs", c.startMs());
            m.put("endMs", c.endMs());
            if (c.releaseMs() != null) {
                m.put("releaseMs", c.releaseMs());
            }
            if (c.anchorCamera() != null) {
                m.put("anchorCamera", c.anchorCamera());
            }
            if (c.zoneId() != null) {
                m.put("zoneId", c.zoneId());
            }
            if (c.phases() != null) {
                m.put("phases", c.phases());
            }
            if (c.shotMade() != null) {
                m.put("shotMade", c.shotMade());
            }
            if (c.score() != null) {
                m.put("score", c.score());
            }
            if (motionUri != null) {
                m.put("motionUri", motionUri);   // 同 session 各 clip 共用逐帧文件,motionRange 区分
            }
            if (c.motionRange() != null) {
                m.put("motionRange", c.motionRange());
            }
            items.add(m);
        }
        try {
            if (mq != null) {
                mq.sendActionClips(sessionId, items);
            } else {
                cloud.pushActionClips(sessionId, items);
            }
        } catch (RuntimeException e) {
            log.warn("动作片段上报失败 session={} clips={}: {}", sessionId, items.size(), e.getMessage());
        }
    }

    private void pushGalleries(UUID sessionId, Path dir, SessionHandoff h) {
        if (h.galleries() == null) {
            return;
        }
        for (SessionHandoff.GalleryPart g : h.galleries()) {
            try {
                int version = g.version() != null ? g.version() : 1;
                String storageUri = null;
                if (g.storageRelPath() != null) {
                    Path gp = dir.resolve(g.storageRelPath());
                    if (Files.exists(gp)) {
                        String key = "gallery/" + g.studentId() + "/v" + version + "/" + gp.getFileName();
                        storageUri = objectStore.put(key, gp, null).uri();
                    } else {
                        log.warn("gallery 特征文件缺失 session={} path={}", sessionId, gp);
                    }
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("studentId", g.studentId() == null ? null : g.studentId().toString());
                body.put("version", version);
                // storage_uri 云端 @NotBlank:上传失败则给占位 uri,元数据先登记,特征回头补传
                body.put("storageUri", storageUri != null ? storageUri
                        : "s3://" + props.getMinio().getBucket() + "/gallery/" + g.studentId() + "/v" + version);
                if (g.faceModel() != null) {
                    body.put("faceModel", g.faceModel());
                }
                if (g.bodyModel() != null) {
                    body.put("bodyModel", g.bodyModel());
                }
                if (g.faceDim() != null) {
                    body.put("faceDim", g.faceDim());
                }
                if (g.bodyDim() != null) {
                    body.put("bodyDim", g.bodyDim());
                }
                if (g.sampleCount() != null) {
                    body.put("sampleCount", g.sampleCount());
                }
                if (g.enrolledCamera() != null) {
                    body.put("enrolledCamera", g.enrolledCamera());
                }
                if (g.enrolledAt() != null) {
                    body.put("enrolledAt", g.enrolledAt());
                }
                if (mq != null) {
                    mq.sendGallery(body);
                } else {
                    cloud.registerGallery(body);
                }
            } catch (RuntimeException e) {
                log.warn("gallery 登记失败 session={} student={}: {}", sessionId, g.studentId(), e.getMessage());
            }
        }
    }

    /** 交接文件没给 lessonId 时,回落读 edge 写的 meta.json。 */
    private UUID readLessonId(Path dir) {
        try {
            Path meta = dir.resolve(META_FILE);
            if (!Files.exists(meta)) {
                return null;
            }
            Map<?, ?> m = objectMapper.readValue(Files.readString(meta), Map.class);
            Object lid = m.get("lessonId");
            return lid == null || String.valueOf(lid).isBlank() ? null : UUID.fromString(String.valueOf(lid));
        } catch (Exception e) {
            return null;
        }
    }
}
