package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.RosterEntry;
import com.cnsportiot.edge.identity.EnrollLauncher;
import com.cnsportiot.edge.identity.EnrollLauncher.EnrollRunStatus;
import com.cnsportiot.edge.identity.IdentityBindingStore;
import com.cnsportiot.edge.identity.IdentityBindingStore.Binding;
import com.cnsportiot.edge.service.RosterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 现场人脸注册绑定(首次运行:采集人脸→看脸输学号→关联 UUID→本地缓存,不上云)。
 *
 * <p>算法 {@code run_live_ws.py enroll} 已把每人正面注册成 {@code stu_XX} 并写
 * {@code {algoOutputsDir}/{session}/enrollment.json} + 缩略图 {@code enroll_preview/{stu}.jpg}。
 * 本控制器:①列出算法刚注册的 stu_XX(带缩略图链接)供教师看脸;②教师逐个输学号,edge 回填 studentId 并缓存。
 */
@RestController
@RequestMapping("/local/enroll")
public class EnrollBindingController {

    private final EdgeProperties props;
    private final IdentityBindingStore bindings;
    private final RosterService rosterService;
    private final ObjectMapper objectMapper;
    private final EnrollLauncher enrollLauncher;

    public EnrollBindingController(EdgeProperties props, IdentityBindingStore bindings,
                                   RosterService rosterService, ObjectMapper objectMapper,
                                   EnrollLauncher enrollLauncher) {
        this.props = props;
        this.bindings = bindings;
        this.rosterService = rosterService;
        this.objectMapper = objectMapper;
        this.enrollLauncher = enrollLauncher;
    }

    /**
     * 开始现场人脸采集:edge 拉起算法 enroll 进程(异步),立即返回 RUNNING。
     * {@code session} 约定 = 课程 id;算法据此把注册产物写 {@code {algoOutputsDir}/{session}/},
     * 前端轮询 {@link #status} 转 SUCCEEDED 后用同一 session 调 {@link #identities}。
     */
    @PostMapping("/start")
    public ApiResponse<EnrollRunStatus> start(@Valid @RequestBody StartEnrollRequest request) {
        return ApiResponse.ok(enrollLauncher.start(
                request.session(), request.enrollCamera(), request.seconds(),
                request.sampleHz(), request.expectedPersons()));
    }

    /** 采集状态轮询:RUNNING/SUCCEEDED/FAILED/NONE(从未跑过)。前端据此在 SUCCEEDED 后拉注册结果。 */
    @GetMapping("/status")
    public ApiResponse<EnrollRunStatus> status(@RequestParam String session) {
        return ApiResponse.ok(enrollLauncher.status(session));
    }

    /** 列出算法某注册 session 采到的人(stu_XX + 是否有缩略图),供注册页看脸绑学号。 */
    @GetMapping("/identities")
    public ApiResponse<EnrolledIdentities> identities(@RequestParam String session) {
        Path meta = algoSessionDir(session).resolve("enrollment.json");
        if (!Files.exists(meta)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "算法注册产物不存在: " + meta);
        }
        Map<String, Object> m;
        try {
            m = objectMapper.readValue(Files.readString(meta), new TypeReference<>() { });
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "注册产物解析失败: " + e.getMessage());
        }
        Path previewDir = algoSessionDir(session).resolve("enroll_preview");
        List<EnrolledPerson> people = new ArrayList<>();
        Object identities = m.get("identities");
        if (identities instanceof List<?> list && !list.isEmpty()) {
            // 新版:算法给了 {local_id, global_id} → 绑定当场即可建立跨课次映射
            for (Object o : list) {
                if (o instanceof Map<?, ?> im) {
                    String localId = asStr(im.get("local_id"));
                    if (localId == null) {
                        continue;
                    }
                    people.add(new EnrolledPerson(localId, asStr(im.get("global_id")),
                            Files.exists(previewDir.resolve(localId + ".jpg"))));
                }
            }
        } else {
            // 旧版:只有 student_ids(无 global_id)→ global_id 于 run 时补学
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) m.getOrDefault("student_ids", List.of());
            for (String id : ids) {
                people.add(new EnrolledPerson(id, null, Files.exists(previewDir.resolve(id + ".jpg"))));
            }
        }
        return ApiResponse.ok(new EnrolledIdentities(session, String.valueOf(m.get("enroll_camera")), people));
    }

    /** 缩略图(注册页看脸用)。 */
    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@RequestParam String session, @RequestParam String id) {
        Path jpg = algoSessionDir(session).resolve("enroll_preview").resolve(id + ".jpg");
        if (!Files.exists(jpg)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(jpg);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.noCache())
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 教师看脸输学号,批量绑定。edge 从名单回填 studentId/姓名并缓存(global_id 于 run 时学到)。 */
    @PostMapping("/bind")
    public ApiResponse<List<BoundPerson>> bind(@Valid @RequestBody BindRequest request) {
        List<BoundPerson> out = new ArrayList<>();
        for (BindItem it : request.bindings()) {
            RosterEntry entry = rosterService.find(it.studentNo()).orElse(null);
            String studentId = entry != null && entry.studentId() != null ? entry.studentId().toString() : null;
            String displayName = entry != null ? entry.displayName() : null;
            Binding b = new Binding(it.studentNo(), studentId, displayName);
            bindings.bind(it.localId(), it.globalId(), b);
            out.add(new BoundPerson(it.localId(), it.globalId(), it.studentNo(), studentId, displayName, entry != null));
        }
        return ApiResponse.ok(out);
    }

    /** 当前持久人脸绑定(global_id → 学号),排查/回显用。 */
    @GetMapping("/bindings")
    public ApiResponse<Map<String, Binding>> current() {
        return ApiResponse.ok(bindings.persistentBindings());
    }

    private Path algoSessionDir(String session) {
        return Path.of(props.getLive().getAlgoOutputsDir(), session);
    }

    private static String asStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // ---- DTO ----

    /** 开始采集入参。session 必填(= 课程 id);其余可空,空则用 hoopshake.edge.enroll.* 默认。 */
    public record StartEnrollRequest(
            @NotBlank String session,
            String enrollCamera,
            Double seconds,
            Integer sampleHz,
            Integer expectedPersons) {}

    public record EnrolledIdentities(String session, String enrollCamera, List<EnrolledPerson> people) {}

    public record EnrolledPerson(String localId, String globalId, boolean hasThumbnail) {}

    public record BindRequest(@NotEmpty @Valid List<BindItem> bindings) {}

    public record BindItem(
            @NotBlank String localId,
            String globalId,
            @NotBlank String studentNo) {}

    public record BoundPerson(String localId, String globalId, String studentNo,
                              String studentId, String displayName, boolean matchedInRoster) {}
}
