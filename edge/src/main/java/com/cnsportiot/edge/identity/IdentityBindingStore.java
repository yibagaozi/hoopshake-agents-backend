package com.cnsportiot.edge.identity;

import com.cnsportiot.edge.config.EdgeProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 人脸 ↔ 学号 ↔ studentId 绑定表(现场注册时建,本地缓存,不上云)。
 *
 * <p>算法当堂给的是 {@code stu_XX}(当堂序号)和 {@code global_id}(跨课次人脸)。教师注册时“看脸输学号”,
 * edge 据此建绑定:
 * <ul>
 *   <li><b>跨课次</b> {@code global_id → {studentNo, studentId}}:持久化到 {@code data-root/identity/bindings.json},
 *       下次课人脸认出同一 global_id 即自动复用,不必重绑;</li>
 *   <li><b>当堂</b> {@code stu_XX → {...}}:仅内存,注册时先绑上,run 时事件带 global_id 就顺便把
 *       global_id→绑定学到并落盘(见 {@link #resolve})。</li>
 * </ul>
 * studentId(UUID)由 studentNo 经名单回填,得不到就留空——云端入库时还会用 studentNo 再解析一次。
 */
@Component
public class IdentityBindingStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityBindingStore.class);

    /** 一条绑定:学号必填,studentId/displayName 尽力而为。 */
    public record Binding(String studentNo, String studentId, String displayName) {}

    private final EdgeProperties props;
    private final ObjectMapper objectMapper;

    private final ConcurrentMap<String, Binding> byGlobalId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Binding> byLocalId = new ConcurrentHashMap<>();

    public IdentityBindingStore(EdgeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        Path f = bindingFile();
        if (!Files.exists(f)) {
            return;
        }
        try {
            Map<String, Map<String, String>> raw = objectMapper.readValue(
                    Files.readString(f), new TypeReference<>() { });
            raw.forEach((gid, m) -> byGlobalId.put(gid,
                    new Binding(m.get("studentNo"), m.get("studentId"), m.get("displayName"))));
            log.info("已载入人脸绑定表 {} 条 ← {}", byGlobalId.size(), f);
        } catch (Exception e) {
            log.warn("人脸绑定表载入失败(忽略,重新建): {}", e.getMessage());
        }
    }

    /** 注册时绑定当堂 stu_XX;同时若已知 global_id 则一并持久化。 */
    public synchronized Binding bind(String localId, String globalId, Binding binding) {
        if (localId != null && !localId.isBlank()) {
            byLocalId.put(localId, binding);
        }
        if (globalId != null && !globalId.isBlank()) {
            byGlobalId.put(globalId, binding);
            persist();
        }
        return binding;
    }

    /**
     * run 时解析身份:global_id 优先(跨课次),否则用当堂 stu_XX;
     * 当命中的是当堂绑定且事件带了 global_id,顺便把 global_id→绑定学到并落盘(下次课免绑)。
     */
    public Optional<Binding> resolve(String globalId, String localId) {
        if (globalId != null && !globalId.isBlank()) {
            Binding g = byGlobalId.get(globalId);
            if (g != null) {
                return Optional.of(g);
            }
        }
        if (localId != null && !localId.isBlank()) {
            Binding l = byLocalId.get(localId);
            if (l != null) {
                if (globalId != null && !globalId.isBlank()) {
                    byGlobalId.put(globalId, l);
                    persist();
                }
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** 当前持久绑定(global_id → 绑定),供注册页回显/排查。 */
    public Map<String, Binding> persistentBindings() {
        return Map.copyOf(byGlobalId);
    }

    /** 清空当堂 stu_XX 绑定(换课/重注册时);持久 global 表不动。 */
    public void clearSession() {
        byLocalId.clear();
    }

    private synchronized void persist() {
        Path f = bindingFile();
        try {
            Files.createDirectories(f.getParent());
            Map<String, Map<String, String>> raw = new LinkedHashMap<>();
            byGlobalId.forEach((gid, b) -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("studentNo", b.studentNo());
                m.put("studentId", b.studentId());
                m.put("displayName", b.displayName());
                raw.put(gid, m);
            });
            Files.writeString(f, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
        } catch (Exception e) {
            log.warn("人脸绑定表落盘失败(内存仍在,重启后丢): {}", e.getMessage());
        }
    }

    private Path bindingFile() {
        return Path.of(props.getDataRoot(), props.getLive().getBindingRelPath());
    }
}
