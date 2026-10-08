package com.cnsportiot.edge.identity;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.BatchProcessRunner;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 现场人脸采集编排:把算法 {@code run_live_ws.py enroll} 纳入 edge 托管——
 * 前端点“开始采集”→ 本类拉起子进程(单机单采集,同一时刻只允许一个)→ 异步等其结束 →
 * 记录 RUNNING/SUCCEEDED/FAILED 供前端轮询。采集 session 由调用方传入(约定 = 课程 id),
 * 算法据此把注册产物写到 {@code {algoOutputsDir}/{session}/},前端再用同一 session 拉注册结果。
 *
 * <p>进程本身复用 {@link BatchProcessRunner}(通用一次性子进程运行器);采集时长几十秒到数分钟,
 * 走独立的 {@code enrollExecutor} 线程,不阻塞采集/出云链路。
 *
 * <p>也可从本地视频注册(算法 {@code enroll --video}):视频须在导入目录 {@code enroll.video-dir} 内,
 * 可经 {@link #importVideo} 上传进来;产物与直播注册相同,状态/结果接口不变。
 */
@Service
public class EnrollLauncher {

    private static final Logger log = LoggerFactory.getLogger(EnrollLauncher.class);

    /** session 既作命令行参数又作目录名,收紧到安全字符集,防注入/路径穿越(UUID 课程 id 满足)。 */
    private static final Pattern SAFE_SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** 视频注册支持的扩展名(OpenCV/FFmpeg 可解码的常见容器)。 */
    private static final List<String> VIDEO_EXTENSIONS = List.of(
            "mp4", "m4v", "mov", "mkv", "avi", "ts", "flv", "webm", "wmv", "mpg", "mpeg");

    private final EdgeProperties props;
    private final BatchProcessRunner runner;
    private final TaskExecutor enrollExecutor;

    /** 各 session 最近一次运行态(前端按 session 轮询)。 */
    private final Map<String, EnrollRunStatus> runs = new ConcurrentHashMap<>();
    /** 正在跑的 session;null=空闲。单飞闸:非空即拒新任务。 */
    private final AtomicReference<String> active = new AtomicReference<>();

    public EnrollLauncher(EdgeProperties props,
                          BatchProcessRunner runner,
                          @Qualifier("enrollExecutor") TaskExecutor enrollExecutor) {
        this.props = props;
        this.runner = runner;
        this.enrollExecutor = enrollExecutor;
    }

    /**
     * 拉起一次采集。立即返回 RUNNING(异步跑),前端轮询 {@link #status(String)} 直到 SUCCEEDED/FAILED。
     *
     * @param session         采集会话 = 课程 id(仅字母数字 {@code _-},≤64)
     * @param enrollCamera    采集机位;null 用配置默认
     * @param seconds         采集时长秒;null 用配置默认
     * @param sampleHz        采样帧率;null 用配置默认
     * @param expectedPersons 期望人数(核对,可选);null/≤0 不传给算法
     * @throws BusinessException ENROLL_UNAVAILABLE 未启用/未配置;PARAM_INVALID session 非法;ENROLL_BUSY 已有采集在跑
     */
    public EnrollRunStatus start(String session, String enrollCamera, Double seconds,
                                 Integer sampleHz, Integer expectedPersons) {
        return start(session, enrollCamera, seconds, sampleHz, expectedPersons, null);
    }

    /**
     * 同 {@link #start(String, String, Double, Integer, Integer)};{@code video} 非空时改从本地视频注册
     * (算法 {@code enroll --video},需算法增量包 2.2.0+enroll.1)。
     *
     * @param video   导入目录({@code hoopshake.edge.enroll.video-dir})内的相对路径,如 {@code {session}/a.mp4};
     *                null/空 = 直播采集
     * @param seconds 视频模式下为只处理前 N 秒,null = 整段
     * @throws BusinessException 另:PARAM_INVALID 视频不在导入目录内或扩展名不支持;NOT_FOUND 视频不存在;
     *                           ENROLL_UNAVAILABLE 未配置导入目录
     */
    public EnrollRunStatus start(String session, String enrollCamera, Double seconds,
                                 Integer sampleHz, Integer expectedPersons, String video) {
        EdgeProperties.Enroll cfg = props.getEnroll();
        requireEnabled(cfg);
        requireSafeSession(session);
        Path videoFile = isBlank(video) ? null : resolveVideo(cfg, video);
        if (!active.compareAndSet(null, session)) {
            throw new BusinessException(EdgeErrorCode.ENROLL_BUSY, "已有采集任务在进行中: " + active.get());
        }

        List<String> cmd = buildCommand(cfg, session, enrollCamera, seconds, sampleHz, expectedPersons, videoFile);
        File workDir = isBlank(cfg.getWorkDir()) ? null : new File(cfg.getWorkDir());
        Map<String, String> env = cfg.getEnv();
        Duration timeout = cfg.getTimeout();

        OffsetDateTime startedAt = OffsetDateTime.now();
        EnrollRunStatus running = new EnrollRunStatus(session, EnrollState.RUNNING, null,
                videoFile == null ? "采集中" : "视频注册中", startedAt, null);
        runs.put(session, running);

        try {
            enrollExecutor.execute(() -> execute(session, cmd, workDir, env, timeout, startedAt));
        } catch (RuntimeException e) {
            // 提交失败(如线程池拒绝):立即释放闸并标失败,避免卡死后续采集
            active.compareAndSet(session, null);
            EnrollRunStatus failed = new EnrollRunStatus(session, EnrollState.FAILED, null,
                    "采集任务提交失败: " + e.getMessage(), startedAt, OffsetDateTime.now());
            runs.put(session, failed);
            throw new BusinessException(EdgeErrorCode.ENROLL_UNAVAILABLE, failed.message());
        }

        log.info("人脸采集已拉起 session={} cmd={}", session, cmd);
        return running;
    }

    /** 某 session 的采集状态;从未跑过返回 NONE。 */
    public EnrollRunStatus status(String session) {
        EnrollRunStatus s = runs.get(session);
        return s != null ? s : new EnrollRunStatus(session, EnrollState.NONE, null, null, null, null);
    }

    /**
     * 保存上传的注册视频到 {@code {videoDir}/{session}/{文件名}}(同名覆盖),返回给
     * {@link #start(String, String, Double, Integer, Integer, String)} 用的相对路径。
     *
     * @throws BusinessException ENROLL_UNAVAILABLE 未启用/未配置导入目录;PARAM_INVALID session 或扩展名非法;
     *                           ENROLL_BUSY 已有采集在跑(先拒,不落盘)
     */
    public String importVideo(String session, String originalFilename, InputStream content) {
        EdgeProperties.Enroll cfg = props.getEnroll();
        requireEnabled(cfg);
        requireSafeSession(session);
        if (active.get() != null) {
            throw new BusinessException(EdgeErrorCode.ENROLL_BUSY, "已有采集任务在进行中: " + active.get());
        }
        String name = videoFileName(originalFilename);
        Path dir = videoRoot(cfg).resolve(session);
        Path target;
        try {
            target = dir.resolve(name);
        } catch (InvalidPathException e) {
            // 文件系统编码表示不了原名(如非 UTF-8 locale 下的中文名):改用 ASCII 名
            name = "video_" + System.currentTimeMillis() + name.substring(name.lastIndexOf('.'));
            target = dir.resolve(name);
        }
        try {
            Files.createDirectories(dir);
            Path part = Files.createTempFile(dir, ".upload-", ".part");
            try {
                Files.copy(content, part, StandardCopyOption.REPLACE_EXISTING);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(part);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存注册视频失败: " + e.getMessage());
        }
        log.info("注册视频已导入 session={} file={}", session, target);
        return session + "/" + name;
    }

    /** 导入目录(含各 session 子目录)里可用于注册的视频,最近修改的在前;目录不存在返回空。 */
    public List<VideoFile> listVideos() {
        Path root = videoRoot(props.getEnroll());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root, 2)) {
            List<VideoFile> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(p) && isVideoName(p.getFileName().toString())) {
                    out.add(new VideoFile(root.relativize(p).toString().replace('\\', '/'), Files.size(p),
                            OffsetDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault())));
                }
            }
            out.sort(Comparator.comparing(VideoFile::modifiedAt).reversed());
            return out;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取视频导入目录失败: " + e.getMessage());
        }
    }

    private static void requireEnabled(EdgeProperties.Enroll cfg) {
        if (!cfg.isEnabled() || isBlank(cfg.getPythonExecutable()) || isBlank(cfg.getScriptPath())) {
            throw new BusinessException(EdgeErrorCode.ENROLL_UNAVAILABLE,
                    "人脸采集编排未启用或命令未配置(hoopshake.edge.enroll.*)");
        }
    }

    private static void requireSafeSession(String session) {
        if (session == null || !SAFE_SESSION.matcher(session).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "session 非法:仅允许字母数字与 _-,长度 1~64");
        }
    }

    private static Path videoRoot(EdgeProperties.Enroll cfg) {
        if (isBlank(cfg.getVideoDir())) {
            throw new BusinessException(EdgeErrorCode.ENROLL_UNAVAILABLE,
                    "未配置视频导入目录(hoopshake.edge.enroll.video-dir)");
        }
        return Path.of(cfg.getVideoDir()).toAbsolutePath().normalize();
    }

    /** 导入目录内的相对路径 → 绝对路径;不接受目录外(含 .. / 绝对路径 / 指向外部的链接)与非视频扩展名。 */
    private static Path resolveVideo(EdgeProperties.Enroll cfg, String video) {
        Path root = videoRoot(cfg);
        Path file;
        try {
            file = root.resolve(video).normalize();
        } catch (InvalidPathException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "video 路径非法: " + video);
        }
        if (!file.startsWith(root) || !isVideoName(file.getFileName().toString())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "video 须为导入目录内的视频文件(" + String.join("/", VIDEO_EXTENSIONS) + ")");
        }
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "注册视频不存在: " + video);
        }
        try {
            if (!file.toRealPath().startsWith(root.toRealPath())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "video 须为导入目录内的视频文件");
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "注册视频不可读: " + video);
        }
        return file;
    }

    /** 上传文件名 → 存盘名:去目录部分,只留字母/数字/{@code ._ -},须为支持的视频扩展名。 */
    static String videoFileName(String originalFilename) {
        String base = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        String name = base.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        if (name.startsWith(".") || !isVideoName(name)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "仅支持视频文件(" + String.join("/", VIDEO_EXTENSIONS) + "): " + originalFilename);
        }
        return name;
    }

    private static boolean isVideoName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void execute(String session, List<String> cmd, File workDir,
                         Map<String, String> env, Duration timeout, OffsetDateTime startedAt) {
        EnrollRunStatus result;
        try {
            int code = runner.run(cmd, workDir, env, timeout);
            result = code == 0
                    ? new EnrollRunStatus(session, EnrollState.SUCCEEDED, 0, "采集完成", startedAt, OffsetDateTime.now())
                    : new EnrollRunStatus(session, EnrollState.FAILED, code,
                            "采集进程非零退出: " + code, startedAt, OffsetDateTime.now());
        } catch (RuntimeException e) {
            log.warn("人脸采集进程失败 session={}", session, e);
            result = new EnrollRunStatus(session, EnrollState.FAILED, null,
                    "采集失败: " + e.getMessage(), startedAt, OffsetDateTime.now());
        } finally {
            active.compareAndSet(session, null);
        }
        runs.put(session, result);
    }

    private static List<String> buildCommand(EdgeProperties.Enroll cfg, String session, String enrollCamera,
                                             Double seconds, Integer sampleHz, Integer expectedPersons,
                                             Path videoFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getPythonExecutable());
        cmd.add(cfg.getScriptPath());
        cmd.add("enroll");
        cmd.add("--session");
        cmd.add(session);
        cmd.add("--enroll-camera");
        cmd.add(!isBlank(enrollCamera) ? enrollCamera : cfg.getEnrollCamera());
        if (videoFile != null) {
            // 视频注册:无预览窗口;不给 --seconds 即处理整段
            cmd.add("--video");
            cmd.add(videoFile.toString());
            cmd.add("--no-preview");
            if (seconds != null) {
                cmd.add("--seconds");
                cmd.add(String.valueOf(seconds));
            }
        } else {
            cmd.add("--seconds");
            cmd.add(String.valueOf(seconds != null ? seconds : cfg.getSeconds()));
        }
        cmd.add("--sample-hz");
        cmd.add(String.valueOf(sampleHz != null ? sampleHz : cfg.getSampleHz()));
        if (expectedPersons != null && expectedPersons > 0) {
            cmd.add("--expected-persons");
            cmd.add(String.valueOf(expectedPersons));
        }
        if (cfg.getExtraArgs() != null) {
            cmd.addAll(cfg.getExtraArgs());
        }
        return cmd;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 采集状态机。NONE=未跑过。 */
    public enum EnrollState {
        RUNNING, SUCCEEDED, FAILED, NONE
    }

    /** 导入目录里的一个视频:{@code video} 即 start 请求的 video 字段取值。 */
    public record VideoFile(String video, long sizeBytes, OffsetDateTime modifiedAt) {
    }

    /** 采集运行态(前端轮询用)。 */
    public record EnrollRunStatus(
            String session,
            EnrollState state,
            Integer exitCode,
            String message,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }
}
