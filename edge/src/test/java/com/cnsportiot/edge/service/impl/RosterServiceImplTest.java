package com.cnsportiot.edge.service.impl;

import com.cnsportiot.edge.cloudsync.CloudIngestClient;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.RosterEntry;
import com.cnsportiot.edge.dto.RosterDtos.RosterResponse;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.identity.IdentityBindingStore.Binding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RosterServiceImplTest {

    private static final UUID LESSON = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID STUDENT = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");

    @Test
    void current_reflectsLocalFaceBindingBeforeCloudGalleryUpload(@TempDir Path root) {
        EdgeProperties props = new EdgeProperties();
        props.setDataRoot(root.toString());
        IdentityBindingStore bindings = new IdentityBindingStore(props, JsonMapper.builder().build());
        CloudIngestClient cloud = mock(CloudIngestClient.class);
        when(cloud.fetchRoster(LESSON)).thenReturn(List.of(new RosterEntry(
                STUDENT, "2021001", "张三", null, false,
                null, null, null, null, null)));
        RosterServiceImpl service = new RosterServiceImpl(cloud, bindings);

        service.sync(LESSON);
        assertThat(service.current().students().get(0).galleryReady()).isFalse();

        bindings.bind(LESSON.toString(), "stu_00", "stu_global_01",
                new Binding("2021001", STUDENT.toString(), "张三"));

        RosterResponse response = service.current();
        assertThat(response.students().get(0).galleryReady()).isTrue();
        assertThat(response.galleryReadyCount()).isEqualTo(1);
    }
}
