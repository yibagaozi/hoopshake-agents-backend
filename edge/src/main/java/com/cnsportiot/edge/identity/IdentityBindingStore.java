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
 * <p>算法当堂给的是 {@code stu_XX}(当堂序号)和 {@code global_id}(跨课次人脸,当前算法版本可能缺)。
 * 教师注册时“看脸输学号”,edge 据此建绑定,三处存储都会落盘到 {@code data-root/identity/bindings.json}:
 * <ul>
 *   <li><b>跨课次</b> {@code global_id → 绑定}:下次课人脸认出同一 global_id 即自动复用,不必重绑;</li>
 *   <li><b>按注册会话</b> {@code session(=课程id) → {stu_XX → 绑定}}:即使算法没给 global_id 也能持久保存,
 *       注册页用同一 session 重新打开时据此回显“已绑定 + 学号”;这是本表最常用的一档(算法暂无 global_id 时);</li>
 *   <li><b>当堂内存</b> {@code stu_XX → 绑定}:run 时事件带 global_id 就顺便把 global_id→绑定学到并落盘(见 {@link #resolve})。</li>
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

    /** 跨课次:global_id → 绑定(持久)。 */
    private final ConcurrentMap<String, Binding> byGlobalId = new ConcurrentHashMap<>();
    /** 按注册会话:session → (stu_XX → 绑定)(持久)。算法无 global_id 时靠这档回显与保存。 */
    private final ConcurrentMap<String, ConcurrentMap<String, Binding>> bySession = new ConcurrentHashMap<>();
    /** 当堂内存:stu_XX → 绑定(不持久,供 run 时按当堂序号兜底解析)。 */
    private final ConcurrentMap<String, Binding> byLocalId = new ConcurrentHashMap<>();

    public IdentityBindingStore(EdgeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        Path f = bindingFile();
        if (!Files.exists(f)) {
            return;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(Files.readString(f), new TypeReference<>() { });
            if (root.containsKey("global") || root.containsKey("sessions")) {
                // 新格式 {global:{gid:binding}, sessions:{sid:{lid:binding}}}
                Object g = root.get("global");
                if (g instanceof Map<?, ?> gm) {
                    gm.forEach((gid, v) -> byGlobalId.put(String.valueOf(gid), toBinding(v)));
                }
                Object s = root.get("sessions");
                if (s instanceof Map<?, ?> sm) {
                    sm.forEach((sid, lm) -> {
                        if (lm instanceof Map<?, ?> lmm) {
                            ConcurrentMap<String, Binding> inner = new ConcurrentHashMap<>();
                            lmm.forEach((lid, v) -> inner.put(String.valueOf(lid), toBinding(v)));
                            bySession.put(String.valueOf(sid), inner);
                        }
                    });
                }
            } else {
                // 兼容旧格式:顶层即 gid → 绑定
                root.forEach((gid, v) -> byGlobalId.put(gid, toBinding(v)));
            }
            log.info("已载入人脸绑定表 global={} sessions={} ← {}", byGlobalId.size(), bySession.size(), f);
        } catch (Exception e) {
            log.warn("人脸绑定表载入失败(忽略,重新建): {}", e.getMessage());
        }
    }

    /**
     * 注册时按会话绑定(推荐,注册页调用):把 {@code session(=课程id)+stu_XX → 绑定} 持久保存,
     * 同时若已知 global_id 也一并持久化;当堂内存表也写一份供 run 兜底。即使无 global_id 也会落盘。
     */
    public synchronized Binding bind(String session, String localId, String globalId, Binding binding) {
        if (localId != null && !localId.isBlank()) {
            byLocalId.put(localId, binding);
            if (session != null && !session.isBlank()) {
                bySession.computeIfAbsent(session, k -> new ConcurrentHashMap<>()).put(localId, binding);
            }
        }
        if (globalId != null && !globalId.isBlank()) {
            byGlobalId.put(globalId, binding);
        }
        persist();
        return binding;
    }

    /** 无会话上下文的绑定(run 兜底/旧调用):只写当堂内存 + 有 global 才持久。 */
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
     * run 时解析身份(带会话上下文,推荐)。按优先级:
     * <ol>
     *   <li>{@code global_id}(跨课次,持久);</li>
     *   <li>{@code session(=课程id) + stu_XX}(<b>持久,重启后仍在</b>;算法没给 global_id 时的主路径);</li>
     *   <li>当堂内存 {@code stu_XX}(不持久,仅同一 JVM 内兜底)。</li>
     * </ol>
     * 命中会话档/当堂档且事件带了 global_id,顺便把 {@code global_id → 绑定}学到并落盘(下次课免绑)。
     *
     * <p>修复:此前只查 {@code byGlobalId}/{@code byLocalId},而 {@code byLocalId} 是内存态、重启后为空,
     * 算法又常无 {@code global_id} → 重启后解析不到 → displayName 回落成 {@code stu_XX}。现补查持久的 {@code bySession}。
     */
    public Optional<Binding> resolve(String session, String globalId, String localId) {
        if (globalId != null && !globalId.isBlank()) {
            Binding g = byGlobalId.get(globalId);
            if (g != null) {
                return Optional.of(g);
            }
        }
        if (session != null && !session.isBlank() && localId != null && !localId.isBlank()) {
            ConcurrentMap<String, Binding> inner = bySession.get(session);
            Binding s = inner == null ? null : inner.get(localId);
            if (s != null) {
                learnGlobal(globalId, s);
                return Optional.of(s);
            }
        }
        if (localId != null && !localId.isBlank()) {
            Binding l = byLocalId.get(localId);
            if (l != null) {
                learnGlobal(globalId, l);
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** 旧签名(无会话上下文):等价于 {@code resolve(null, globalId, localId)},不查按会话档。 */
    public Optional<Binding> resolve(String globalId, String localId) {
        return resolve(null, globalId, localId);
    }

    /** 命中非 global 档且事件带 global_id 时,补学一条跨课次映射并落盘(仅首次,避免每投一次都写盘)。 */
    private void learnGlobal(String globalId, Binding b) {
        if (globalId != null && !globalId.isBlank() && byGlobalId.putIfAbsent(globalId, b) == null) {
            persist();
        }
    }

    /** 某注册会话某 stu_XX 的绑定(注册页回显“已绑定”用);无则 null。 */
    public Binding forSessionLocal(String session, String localId) {
        if (session == null || localId == null) {
            return null;
        }
        ConcurrentMap<String, Binding> inner = bySession.get(session);
        return inner == null ? null : inner.get(localId);
    }

    /** 某 global_id 的绑定;无则 null。 */
    public Binding forGlobal(String globalId) {
        return globalId == null ? null : byGlobalId.get(globalId);
    }

    /** 某课程会话某 stu_XX 已学到的 global_id;算法未给时为 null。 */
    public String globalIdForSessionLocal(String session, String localId) {
        Binding binding = forSessionLocal(session, localId);
        if (binding == null) {
            return null;
        }
        return byGlobalId.entrySet().stream()
                .filter(entry -> binding.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** 当前持久跨课次绑定(global_id → 绑定),排查/回显用。 */
    public Map<String, Binding> persistentBindings() {
        return Map.copyOf(byGlobalId);
    }

    /** 某注册会话的全部绑定(stu_XX → 绑定),排查/回显用。 */
    public Map<String, Binding> sessionBindings(String session) {
        ConcurrentMap<String, Binding> inner = bySession.get(session);
        return inner == null ? Map.of() : Map.copyOf(inner);
    }

    /** 判断某课程会话内该学号是否已完成现场人脸绑定。 */
    public boolean isStudentNoBound(String session, String studentNo) {
        if (session == null || studentNo == null) {
            return false;
        }
        ConcurrentMap<String, Binding> inner = bySession.get(session);
        return inner != null && inner.values().stream()
                .anyMatch(binding -> studentNo.equals(binding.studentNo()));
    }

    /** 清空当堂 stu_XX 内存绑定(换课/重注册时);持久表不动。 */
    public void clearSession() {
        byLocalId.clear();
    }

    private synchronized void persist() {
        Path f = bindingFile();
        try {
            Files.createDirectories(f.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            Map<String, Object> global = new LinkedHashMap<>();
            byGlobalId.forEach((gid, b) -> global.put(gid, bindingMap(b)));
            Map<String, Object> sessions = new LinkedHashMap<>();
            bySession.forEach((sid, inner) -> {
                Map<String, Object> lm = new LinkedHashMap<>();
                inner.forEach((lid, b) -> lm.put(lid, bindingMap(b)));
                sessions.put(sid, lm);
            });
            root.put("global", global);
            root.put("sessions", sessions);
            Files.writeString(f, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("人脸绑定表落盘失败(内存仍在,重启后丢): {}", e.getMessage());
        }
    }

    private static Binding toBinding(Object v) {
        if (v instanceof Map<?, ?> m) {
            return new Binding(str(m.get("studentNo")), str(m.get("studentId")), str(m.get("displayName")));
        }
        return new Binding(null, null, null);
    }

    private static Map<String, String> bindingMap(Binding b) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("studentNo", b.studentNo());
        m.put("studentId", b.studentId());
        m.put("displayName", b.displayName());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Path bindingFile() {
        return Path.of(props.getDataRoot(), props.getLive().getBindingRelPath());
    }
}
