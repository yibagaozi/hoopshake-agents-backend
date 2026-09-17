package com.cnsportiot.edge.calibration;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 球场标定编排,产物按课程隔离:{@code {workDir}/{outputRoot}/{dirPrefix}{课程id}/}
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    /** session 既作命令行参数又作目录名,收紧字符集防注入/路径穿越(UUID 课程 id 满足) */
    private static final Pattern SAFE_SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final EdgeProperties props;
    private final BatchProcessRunner runner;
    private final TaskExecutor calibrationExecutor;

    /** 各 session 最近一次标定运行态(前端轮询) */
    private final Map<String, CalibrationRun> runs = new ConcurrentHashMap<>();
    /** 正在标定的 session;null=空闲。单飞闸 */
    private final AtomicReference<String> active = new AtomicReference<>();

    public CalibrationService(EdgeProperties props,
                              BatchProcessRunner runner,
                              @Qualifier("calibrationExecutor") TaskExecutor calibrationExecutor) {
        this.props = props;
        this.runner = runner;
        this.calibrationExecutor = calibrationExecutor;
    }

    // ---- 产物状态 ----

    /**
     * 查这节课的标定产物状态。只读文件系统,不拉进程
     *
     * @param session 课程 id
     */
    public CalibrationStatus status(String session) {
        String s = requireSafeSession(session);
        EdgeProperties.Calibration cfg = props.getCalibration();
        Path dir = artifactDir(s);

        List<String> missing = new ArrayList<>();
        List<String> present = new ArrayList<>();
        for (String f : expectedFiles(cfg)) {
            if (Files.isRegularFile(dir.resolve(f))) {
                present.add(f);
            } else {
                missing.add(f);
            }
        }
        boolean dirExists = Files.isDirectory(dir);
        return new CalibrationStatus(
                s,
                dir.toString(),
                dirExists && missing.isEmpty(),
                dirExists,
                present,
                missing,
                lastModified(dir, present),
                runs.get(s));
    }

    /**
     * 上课前的标定闸:产物齐 → 返回 true(可启动 CV);缺 → 记一条可操作的告警并返回 false
     *
     * @return true=标定就绪,调用方可以启动 CV;false=未就绪
     */
    public boolean ensureReady(String session) {
        EdgeProperties.Calibration cfg = props.getCalibration();
        CalibrationStatus st = status(session);
        if (st.ready()) {
            return true;
        }
        log.warn("本课标定产物缺失 session={} dir={} 缺={};"
                        + "请在算法机上完成标定(run_live_ws.py calibrate --session {}),"
                        + "或经 POST /local/calibration/run 触发后完成标注",
                st.session(), st.artifactDir(), st.missingFiles(), st.session());
        // 默认不自动拉起:标定要人工标注,后台拉只会弹个没人管的窗口(见类注释)
        if (cfg.isEnabled() && cfg.isAutoOnStart()) {
            try {
                start(session, false);
                log.info("已自动拉起标定 session={}(auto-on-start=true);需有人在算法机前完成标注", st.session());
            } catch (BusinessException e) {
                // 已有标定在跑、或编排不可用:不把上课整个搞失败,由前端按状态接口决定下一步
                log.warn("自动标定未能拉起 session={}: {}", st.session(), e.getMessage());
            }
        }
        return false;
    }

    // ---- 触发标定 ----

    /**
     * 拉起一次标定(异步)。立即返回 RUNNING,前端轮询 {@link #status}
     *
     * @param force true=产物已齐也重标(镜头动过/换场地)
     * @throws BusinessException CALIBRATION_UNAVAILABLE 未启用或命令未配;
     *                           CALIBRATION_BUSY 已有标定在跑;PARAM_INVALID session 非法
     */
    public CalibrationRun start(String session, boolean force) {
        String s = requireSafeSession(session);
        EdgeProperties.Calibration cfg = props.getCalibration();
        if (!cfg.isEnabled() || isBlank(cfg.getPythonExecutable()) || isBlank(cfg.getScriptPath())) {
            throw new BusinessException(EdgeErrorCode.CALIBRATION_UNAVAILABLE,
                    "标定编排未启用或命令未配置(hoopshake.edge.calibration.*)");
        }
        if (!force && status(s).ready()) {
            // 已就绪又不强制:直接回上次结果,避免误触重标把好产物覆盖掉
            CalibrationRun last = runs.get(s);
            return last != null ? last : new CalibrationRun(s, CalibrationState.SUCCEEDED, 0,
                    "标定产物已存在,未重复执行", null, null);
        }
        if (!active.compareAndSet(null, s)) {
            throw new BusinessException(EdgeErrorCode.CALIBRATION_BUSY,
                    "已有标定任务在进行中: " + active.get());
        }

        List<String> cmd = buildCommand(cfg, s);
        File workDir = isBlank(cfg.getWorkDir()) ? null : new File(cfg.getWorkDir());
        Map<String, String> env = new LinkedHashMap<>(cfg.getEnv());
        Duration timeout = cfg.getTimeout();

        OffsetDateTime startedAt = OffsetDateTime.now();
        CalibrationRun running = new CalibrationRun(s, CalibrationState.RUNNING, null, "标定中", startedAt, null);
        runs.put(s, running);

        try {
            calibrationExecutor.execute(() -> execute(s, cmd, workDir, env, timeout, startedAt));
        } catch (RuntimeException e) {
            active.compareAndSet(s, null);
            CalibrationRun failed = new CalibrationRun(s, CalibrationState.FAILED, null,
                    "标定任务提交失败: " + e.getMessage(), startedAt, OffsetDateTime.now());
            runs.put(s, failed);
            throw new BusinessException(EdgeErrorCode.CALIBRATION_UNAVAILABLE, failed.message());
        }

        log.info("标定已拉起 session={} cmd={}", s, cmd);
        return running;
    }

    // ---- 内部 ----

    private void execute(String session, List<String> cmd, File workDir,
                         Map<String, String> env, Duration timeout, OffsetDateTime startedAt) {
        CalibrationRun result;
        try {
            int code = runner.run(cmd, workDir, env, timeout);
            if (code != 0) {
                result = new CalibrationRun(session, CalibrationState.FAILED, code,
                        "标定进程非零退出: " + code, startedAt, OffsetDateTime.now());
            } else if (!status(session).ready()) {
                // 退出码 0 但产物不齐:当作失败,否则上课闸会误以为可以跑 CV
                result = new CalibrationRun(session, CalibrationState.FAILED, 0,
                        "标定进程已结束但产物不完整: " + status(session).missingFiles(),
                        startedAt, OffsetDateTime.now());
            } else {
                result = new CalibrationRun(session, CalibrationState.SUCCEEDED, 0,
                        "标定完成", startedAt, OffsetDateTime.now());
            }
        } catch (RuntimeException e) {
            log.warn("标定进程失败 session={}", session, e);
            result = new CalibrationRun(session, CalibrationState.FAILED, null,
                    "标定失败: " + e.getMessage(), startedAt, OffsetDateTime.now());
        } finally {
            active.compareAndSet(session, null);
        }
        runs.put(session, result);
    }

    /** {@code {workDir}/{outputRoot}/{dirPrefix}{session}} */
    private Path artifactDir(String session) {
        EdgeProperties.Calibration cfg = props.getCalibration();
        Path root = Path.of(cfg.getOutputRoot() == null ? "data/calibration" : cfg.getOutputRoot());
        if (!root.isAbsolute() && !isBlank(cfg.getWorkDir())) {
            root = Path.of(cfg.getWorkDir()).resolve(root);
        }
        String prefix = cfg.getDirPrefix() == null ? "" : cfg.getDirPrefix();
        return root.resolve(prefix + session);
    }

    /** 必备产物 = requiredFiles + 每个机位一份 {cam}.json */
    private static List<String> expectedFiles(EdgeProperties.Calibration cfg) {
        List<String> files = new ArrayList<>();
        if (cfg.getRequiredFiles() != null) {
            files.addAll(cfg.getRequiredFiles());
        }
        if (cfg.getRequiredCameras() != null) {
            for (String cam : cfg.getRequiredCameras()) {
                files.add(cam + ".json");
            }
        }
        return files;
    }

    /** 产物最后更新时刻(取已存在文件里最新的),用于前端显示"标定于 …" */
    private static OffsetDateTime lastModified(Path dir, List<String> present) {
        OffsetDateTime newest = null;
        for (String f : present) {
            try {
                OffsetDateTime t = Files.getLastModifiedTime(dir.resolve(f))
                        .toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
                if (newest == null || t.isAfter(newest)) {
                    newest = t;
                }
            } catch (Exception ignore) {
                // 读不到时间不影响就绪判定
            }
        }
        return newest;
    }

    private static List<String> buildCommand(EdgeProperties.Calibration cfg, String session) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getPythonExecutable());
        cmd.add(cfg.getScriptPath());
        cmd.add("calibrate");
        cmd.add("--session");
        cmd.add(session);
        if (cfg.getExtraArgs() != null) {
            cmd.addAll(cfg.getExtraArgs());
        }
        return cmd;
    }

    private static String requireSafeSession(String session) {
        if (session == null || !SAFE_SESSION.matcher(session).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "session 非法:仅允许字母数字与 _-,长度 1~64");
        }
        return session;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 标定任务状态机。NONE=本进程内没跑过 */
    public enum CalibrationState {
        RUNNING, SUCCEEDED, FAILED, NONE
    }

    /** 一次标定运行态 */
    public record CalibrationRun(
            String session,
            CalibrationState state,
            Integer exitCode,
            String message,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }

    /**
     * 标定产物状态
     *
     * @param ready        必备产物是否齐全 —— 上课闸只看这个
     * @param missingFiles 缺哪些文件,便于现场定位
     * @param lastRun      本进程内最近一次标定任务;从未跑过为 null
     */
    public record CalibrationStatus(
            String session,
            String artifactDir,
            boolean ready,
            boolean dirExists,
            List<String> presentFiles,
            List<String> missingFiles,
            OffsetDateTime calibratedAt,
            CalibrationRun lastRun) {
    }
}
