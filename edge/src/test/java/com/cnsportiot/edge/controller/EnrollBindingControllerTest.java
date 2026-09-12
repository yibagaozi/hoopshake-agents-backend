package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.controller.EnrollBindingController.BindItem;
import com.cnsportiot.edge.controller.EnrollBindingController.BindRequest;
import com.cnsportiot.edge.controller.EnrollBindingController.BoundPerson;
import com.cnsportiot.edge.controller.EnrollBindingController.EnrolledIdentities;
import com.cnsportiot.edge.domain.RosterEntry;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.service.RosterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
                mock(RosterService.class), mapper);

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
                mock(RosterService.class), mapper);

        EnrolledIdentities data = c.identities("live_y").data();
        assertThat(data.people()).hasSize(1);
        assertThat(data.people().get(0).globalId()).isNull();
    }

    @Test
    void bind_resolvesStudentIdFromRoster_andPersistsGlobal(@TempDir Path root, @TempDir Path algoOut) {
        EdgeProperties p = props(root, algoOut);
        IdentityBindingStore store = new IdentityBindingStore(p, mapper);
        store.load();
        RosterService roster = mock(RosterService.class);
        when(roster.find("2021001")).thenReturn(Optional.of(new RosterEntry(
                STU, "2021001", "张三", "RIGHT", null, null, null, null, null)));
        EnrollBindingController c = new EnrollBindingController(p, store, roster, mapper);

        List<BoundPerson> out = c.bind(new BindRequest(List.of(
                new BindItem("stu_00", "stu_global_03", "2021001")))).data();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).studentId()).isEqualTo(STU.toString());
        assertThat(out.get(0).matchedInRoster()).isTrue();
        // global_id 已持久化 → 跨课次可直接命中
        assertThat(store.resolve("stu_global_03", null)).isPresent();
        assertThat(store.persistentBindings()).containsKey("stu_global_03");
    }

    @Test
    void bind_studentNoNotInRoster_stillBindsWithoutStudentId(@TempDir Path root, @TempDir Path algoOut) {
        EdgeProperties p = props(root, algoOut);
        IdentityBindingStore store = new IdentityBindingStore(p, mapper);
        store.load();
        RosterService roster = mock(RosterService.class);
        when(roster.find("9999")).thenReturn(Optional.empty());
        EnrollBindingController c = new EnrollBindingController(p, store, roster, mapper);

        BoundPerson bp = c.bind(new BindRequest(List.of(
                new BindItem("stu_02", "stu_global_09", "9999")))).data().get(0);

        assertThat(bp.studentId()).isNull();          // 名单没查到,studentId 留空(云端仍会按学号解析)
        assertThat(bp.matchedInRoster()).isFalse();
        assertThat(store.resolve("stu_global_09", null)).isPresent();
    }
}
