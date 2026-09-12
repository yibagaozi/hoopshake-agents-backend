package com.cnsportiot.edge.identity;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.identity.IdentityBindingStore.Binding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 人脸绑定表:当堂 stu_XX 绑定、run 时学到 global_id、落盘跨重启复用。 */
class IdentityBindingStoreTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private EdgeProperties props(Path root) {
        EdgeProperties p = new EdgeProperties();
        p.setDataRoot(root.toString());
        return p;
    }

    @Test
    void bindLocal_thenResolveByLocal(@TempDir Path root) {
        IdentityBindingStore store = new IdentityBindingStore(props(root), mapper);
        store.load();
        store.bind("stu_00", null, new Binding("2021001", "uuid-1", "张三"));

        Optional<Binding> b = store.resolve(null, "stu_00");
        assertThat(b).isPresent();
        assertThat(b.get().studentNo()).isEqualTo("2021001");
        assertThat(b.get().studentId()).isEqualTo("uuid-1");
    }

    @Test
    void resolveByGlobal_learnsGlobalFromLocal_andPersists(@TempDir Path root) {
        IdentityBindingStore store = new IdentityBindingStore(props(root), mapper);
        store.load();
        store.bind("stu_00", null, new Binding("2021001", "uuid-1", "张三"));

        // run 时事件带 global_id + 当堂 stu_00 → 命中当堂绑定,并学到 global_id→绑定
        Optional<Binding> b = store.resolve("stu_global_03", "stu_00");
        assertThat(b).isPresent();
        assertThat(store.persistentBindings()).containsKey("stu_global_03");

        // 新实例从盘载入 → 跨课次直接按 global_id 命中(免重绑)
        IdentityBindingStore reloaded = new IdentityBindingStore(props(root), mapper);
        reloaded.load();
        Optional<Binding> again = reloaded.resolve("stu_global_03", null);
        assertThat(again).isPresent();
        assertThat(again.get().studentNo()).isEqualTo("2021001");
    }

    @Test
    void resolve_unknown_empty(@TempDir Path root) {
        IdentityBindingStore store = new IdentityBindingStore(props(root), mapper);
        store.load();
        assertThat(store.resolve("nope", "nada")).isEmpty();
    }
}
