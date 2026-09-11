package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 会话结束出云:读交接文件 → 上传 motion → upsertSession + pushActionClips;HTTP / MQ 选路。 */
class SessionCloudPublisherTest {

    private static final UUID SID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID LESSON = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID STU = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private EdgeProperties props(Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        return p;
    }

    /** 构造 publisher;mq 为 null 时走 HTTP。 */
    @SuppressWarnings("unchecked")
    private SessionCloudPublisher pub(EdgeProperties p, ObjectStore store, CloudIngestClient cloud,
                                      MqIngestPublisher mq) {
        ObjectProvider<MqIngestPublisher> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(mq);
        return new SessionCloudPublisher(p, store, cloud, mapper, op);
    }

    private Path writeSession(Path root) throws Exception {
        Path dir = root.resolve("sessions").resolve(SID.toString());
        Files.createDirectories(dir.resolve("cloud"));
        Files.createDirectories(dir.resolve("export"));
        Files.writeString(dir.resolve("export/motion.jsonl"), "{\"t\":0}\n{\"t\":33}\n");
        String ingest = """
                {
                  "session": {
                    "lessonId": "%s", "status": "SCORED",
                    "coordinateSystem": {"space": "court_world"},
                    "generatedAt": "2026-09-10T00:00:00Z",
                    "motionExportRelPath": "export/motion.jsonl"
                  },
                  "clips": [
                    {"studentId": "%s", "clipIndex": 0, "actionType": "free_throw",
                     "startMs": 4000.0, "endMs": 6000.0, "releaseMs": 5300.0,
                     "phases": [{"name": "release"}], "shotMade": true,
                     "score": {"release_angles": {"right_elbow": 165.0}, "angles_source": "triangulated_3d"},
                     "motionRange": {"start": 120, "end": 180}}
                  ],
                  "galleries": []
                }
                """.formatted(LESSON, STU);
        Files.writeString(dir.resolve("cloud/ingest.json"), ingest);
        return dir;
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_http_uploadsMotion_andPushesClipsWithMotionUriAndScore(@TempDir Path root) throws Exception {
        writeSession(root);
        ObjectStore store = mock(ObjectStore.class);
        when(store.put(any(), any(), any())).thenReturn(new ObjectStore.StoredObject("s3://hoopshake/m.jsonl", 42));
        CloudIngestClient cloud = mock(CloudIngestClient.class);

        pub(props(root), store, cloud, null).publish(SID);   // mq=null → HTTP

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(store).put(keyCap.capture(), any(), eq("application/x-ndjson"));
        assertThat(keyCap.getValue()).contains(SID.toString()).endsWith("motion.jsonl");

        ArgumentCaptor<Map<String, Object>> sessCap = ArgumentCaptor.forClass(Map.class);
        verify(cloud).reportSession(eq(SID), sessCap.capture());
        assertThat(sessCap.getValue()).containsEntry("status", "SCORED")
                .containsEntry("motionExportUri", "s3://hoopshake/m.jsonl")
                .containsEntry("lessonId", LESSON.toString());

        ArgumentCaptor<List<Map<String, Object>>> clipsCap = ArgumentCaptor.forClass(List.class);
        verify(cloud).pushActionClips(eq(SID), clipsCap.capture());
        Map<String, Object> it = clipsCap.getValue().get(0);
        assertThat(it).containsEntry("motionUri", "s3://hoopshake/m.jsonl").containsEntry("actionType", "free_throw");
        assertThat((Map<String, Object>) it.get("score")).containsKey("release_angles");
        verify(cloud, never()).registerGallery(any());
    }

    @Test
    void publish_mq_routesBatchToMq_notHttp(@TempDir Path root) throws Exception {
        writeSession(root);
        ObjectStore store = mock(ObjectStore.class);
        when(store.put(any(), any(), any())).thenReturn(new ObjectStore.StoredObject("s3://hoopshake/m.jsonl", 42));
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        MqIngestPublisher mq = mock(MqIngestPublisher.class);

        pub(props(root), store, cloud, mq).publish(SID);   // mq 存在 → 走 MQ

        verify(mq).sendSession(eq(SID), anyMap());
        verify(mq).sendActionClips(eq(SID), anyList());
        verify(cloud, never()).reportSession(any(), any());
        verify(cloud, never()).pushActionClips(any(), any());
        verify(store).put(any(), any(), any());            // motion 仍上传对象存储(与传输无关)
    }

    @Test
    void publish_missingHandoff_noCloudCalls(@TempDir Path root) {
        ObjectStore store = mock(ObjectStore.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        pub(props(root), store, cloud, null).publish(SID);
        verifyNoInteractions(store, cloud);
    }

    @Test
    void publish_disabled_noop(@TempDir Path root) throws Exception {
        writeSession(root);
        EdgeProperties p = props(root);
        p.getPublish().setEnabled(false);
        ObjectStore store = mock(ObjectStore.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        pub(p, store, cloud, null).publish(SID);
        verifyNoInteractions(store, cloud);
    }
}
