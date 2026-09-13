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

    /** 回归:算法无 global_id 时,按会话绑定必须落盘并跨重启复用(修复前只进内存、重启即丢)。 */
    @Test
    void bindBySession_noGlobalId_persistsAndReloads(@TempDir Path root) {
        IdentityBindingStore store = new IdentityBindingStore(props(root), mapper);
        store.load();
        store.bind("lesson-1", "stu_00", null, new Binding("2021001", "uuid-1", "张三"));

        assertThat(store.forSessionLocal("lesson-1", "stu_00")).isNotNull();
        assertThat(store.persistentBindings()).isEmpty();   // 无 global_id → 跨课次表仍空,但会话表已存

        IdentityBindingStore reloaded = new IdentityBindingStore(props(root), mapper);
        reloaded.load();
        Binding b = reloaded.forSessionLocal("lesson-1", "stu_00");
        assertThat(b).isNotNull();
        assertThat(b.studentNo()).isEqualTo("2021001");
        assertThat(b.studentId()).isEqualTo("uuid-1");
        assertThat(reloaded.sessionBindings("lesson-1")).containsKey("stu_00");
    }

    /**
     * 回归(displayName 回落成 stu_XX 的根因):算法无 global_id、edge 重启后当堂内存空,
     * {@code resolve} 必须能按 {@code session + stu_XX} 命中持久绑定。修复前 resolve 不查 bySession → 返回空。
     */
    @Test
    void resolveBySession_afterReload_noGlobalId_hitsPersistentBinding(@TempDir Path root) {
        IdentityBindingStore store = new IdentityBindingStore(props(root), mapper);
        store.load();
        store.bind("lesson-1", "stu_00", null, new Binding("2021001", "uuid-1", "张三"));

        // 模拟 edge 重启:新实例只从盘载入(byLocalId 内存态为空)
        IdentityBindingStore reloaded = new IdentityBindingStore(props(root), mapper);
        reloaded.load();

        // 无 session → 只查 byGlobalId/byLocalId,重启后都空 → 解析不到(即修复前的错误路径)
        assertThat(reloaded.resolve(null, "stu_00")).isEmpty();

        // 带 session(=课程id)→ 命中持久的 bySession,displayName 正确
        Optional<Binding> b = reloaded.resolve("lesson-1", null, "stu_00");
        assertThat(b).isPresent();
        assertThat(b.get().studentNo()).isEqualTo("2021001");
        assertThat(b.get().displayName()).isEqualTo("张三");
    }

    /** 命中会话档时若事件带了 global_id,顺便补学跨课次映射并落盘(下次课可直接按 global_id 命中)。 */
    @Test
    void resolveBySession_withGlobalId_learnsGlobal(@TempDir Path root) {
        IdentityBindingStore store = new IdentityBindingStore(props(root), mapper);
        store.load();
        store.bind("lesson-1", "stu_00", null, new Binding("2021001", "uuid-1", "张三"));

        Optional<Binding> b = store.resolve("lesson-1", "stu_global_07", "stu_00");
        assertThat(b).isPresent();
        assertThat(store.persistentBindings()).containsKey("stu_global_07");
    }
}
