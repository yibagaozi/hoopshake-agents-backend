package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.cloudsync.CloudIngestClient;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.controller.EnrollBindingController.BindItem;
import com.cnsportiot.edge.controller.EnrollBindingController.BindRequest;
import com.cnsportiot.edge.controller.EnrollBindingController.BoundPerson;
import com.cnsportiot.edge.controller.EnrollBindingController.EnrolledIdentities;
import com.cnsportiot.edge.controller.EnrollBindingController.StartEnrollRequest;
import com.cnsportiot.edge.domain.RosterEntry;
import com.cnsportiot.edge.identity.EnrollLauncher;
import com.cnsportiot.edge.identity.EnrollLauncher.EnrollRunStatus;
import com.cnsportiot.edge.identity.EnrollLauncher.EnrollState;
import com.cnsportiot.edge.identity.EnrollLauncher.VideoFile;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.service.RosterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 现场注册绑定:列出算法注册的人(带/不带 global_id)+ 看脸输学号 → 回填 studentId + 缓存。 */
class EnrollBindingControllerTest {

    private static final UUID STU = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private EdgeProperties props(Path root, Path algoOut) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        p.getLive().setAlgoOutputsDir(algoOut.toString());
        return p;
    }

    private void writeEnrollment(Path algoOut, String session, String json) throws Exception {
        Path dir = algoOut.resolve(session);
        Files.createDirectories(dir.resolve("enroll_preview"));
        Files.writeString(dir.resolve("enrollment.json"), json);
        Files.writeString(dir.resolve("enroll_preview").resolve("stu_00.jpg"), "jpgbytes");
    }

    @Test
    void identities_withGlobalId(@TempDir Path root, @TempDir Path algoOut) throws Exception {
        writeEnrollment(algoOut, "live_x", """
                {"session_id":"live_x","enroll_camera":"cam_02",
                 "student_ids":["stu_00","stu_01"],
                 "identities":[{"local_id":"stu_00","global_id":"stu_global_03"},
                               {"local_id":"stu_01","global_id":"stu_global_07"}]}
                """);
        EnrollBindingController c = new EnrollBindingController(
                props(root, algoOut), new IdentityBindingStore(props(root, algoOut), mapper),
                mock(RosterService.class), mapper, mock(EnrollLauncher.class), mock(CloudIngestClient.class));

        ApiResponse<EnrolledIdentities> r = c.identities("live_x");
        assertThat(r.data().people()).hasSize(2);
        assertThat(r.data().people().get(0).localId()).isEqualTo("stu_00");
        assertThat(r.data().people().get(0).globalId()).isEqualTo("stu_global_03");
        assertThat(r.data().people().get(0).hasThumbnail()).isTrue();
    }

    @Test
    void identities_fallbackStudentIds_noGlobalId(@TempDir Path root, @TempDir Path algoOut) throws Exception {
        writeEnrollment(algoOut, "live_y", """
                {"session_id":"live_y","enroll_camera":"cam_02","student_ids":["stu_00"]}
                """);
        EnrollBindingController c = new EnrollBindingController(
                props(root, algoOut), new IdentityBindingStore(props(root, algoOut), mapper),
                mock(RosterService.class), mapper, mock(EnrollLauncher.class), mock(CloudIngestClient.class));

        EnrolledIdentities data = c.identities("live_y").data();
        assertThat(data.people()).hasSize(1);
        assertThat(data.people().get(0).globalId()).isNull();
    }

    @Test
    void bind_persistsLocally_andSyncsGlobalIdToCloud(@TempDir Path root, @TempDir Path algoOut) {
        EdgeProperties edgeProps = props(root, algoOut);
        IdentityBindingStore store = new IdentityBindingStore(edgeProps, mapper);
        RosterService roster = mock(RosterService.class);
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        UUID lessonId = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
        when(roster.find("2021001")).thenReturn(Optional.of(new RosterEntry(
                STU, "2021001", "张三", null, false,
                null, null, null, null, null)));
        EnrollBindingController controller = new EnrollBindingController(
                edgeProps, store, roster, mapper, mock(EnrollLauncher.class), cloud);

        var response = controller.bind(new BindRequest(lessonId.toString(), List.of(
                new BindItem("stu_00", "stu_global_01", "2021001"))));

        assertThat(response.data()).singleElement().satisfies(person -> {
            assertThat(person.displayName()).isEqualTo("张三");
            assertThat(person.cloudSynced()).isTrue();
        });
        assertThat(store.forGlobal("stu_global_01").studentNo()).isEqualTo("2021001");
        verify(cloud).syncFaceBinding(lessonId, "stu_00", "stu_global_01",
                STU.toString(), "2021001", edgeProps.getEdgeId());
    }

    @Test
    void importVideo_savesUploadThenStartsVideoEnrollment(@TempDir Path root, @TempDir Path algoOut) throws Exception {
        EnrollLauncher launcher = mock(EnrollLauncher.class);
        MockMultipartFile upload = new MockMultipartFile("file", "enroll.mp4", "video/mp4", new byte[]{1, 2, 3});
        when(launcher.importVideo(eq("s1"), eq("enroll.mp4"), any(InputStream.class))).thenReturn("s1/enroll.mp4");
        EnrollRunStatus running = new EnrollRunStatus("s1", EnrollState.RUNNING, null, "视频注册中", null, null);
        when(launcher.start("s1", null, null, 8, 30, "s1/enroll.mp4")).thenReturn(running);
        EnrollBindingController c = new EnrollBindingController(
                props(root, algoOut), new IdentityBindingStore(props(root, algoOut), mapper),
                mock(RosterService.class), mapper, launcher, mock(CloudIngestClient.class));

        assertThat(c.importVideo("s1", upload, null, 8, 30).data()).isSameAs(running);
        verify(launcher).start("s1", null, null, 8, 30, "s1/enroll.mp4");
    }

    @Test
    void start_withVideo_passesItToLauncher(@TempDir Path root, @TempDir Path algoOut) {
        EnrollLauncher launcher = mock(EnrollLauncher.class);
        EnrollBindingController c = new EnrollBindingController(
                props(root, algoOut), new IdentityBindingStore(props(root, algoOut), mapper),
                mock(RosterService.class), mapper, launcher, mock(CloudIngestClient.class));

        c.start(new StartEnrollRequest("s1", null, 60.0, null, 30, "s1/enroll.mp4"));

        verify(launcher).start("s1", null, 60.0, null, 30, "s1/enroll.mp4");
    }

    @Test
    void videos_listsImportDir(@TempDir Path root, @TempDir Path algoOut) {
        EnrollLauncher launcher = mock(EnrollLauncher.class);
        List<VideoFile> files = List.of(new VideoFile("s1/enroll.mp4", 3, OffsetDateTime.now()));
        when(launcher.listVideos()).thenReturn(files);
        EnrollBindingController c = new EnrollBindingController(
                props(root, algoOut), new IdentityBindingStore(props(root, algoOut), mapper),
                mock(RosterService.class), mapper, launcher, mock(CloudIngestClient.class));

        assertThat(c.videos().data()).isEqualTo(files);
    }

}
