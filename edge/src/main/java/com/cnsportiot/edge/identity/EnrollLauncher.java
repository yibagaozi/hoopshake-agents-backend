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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 现场人脸采集编排:把算法 {@code run_live_ws.py enroll} 纳入 edge 托管——
 * 前端点“开始采集”→ 本类拉起子进程(单机单采集,同一时刻只允许一个)→ 异步等其结束 →
 * 记录 RUNNING/SUCCEEDED/FAILED 供前端轮询。采集 session 由调用方传入(约定 = 课程 id),
 * 算法据此把注册产物写到 {@code {algoOutputsDir}/{session}/},前端再用同一 session 拉注册结果。
 *
 * <p>进程本身复用 {@link BatchProcessRunner}(通用一次性子进程运行器);采集时长几十秒到数分钟,
 * 走独立的 {@code enrollExecutor} 线程,不阻塞采集/出云链路。
 */
@Service
public class EnrollLauncher {

    private static final Logger log = LoggerFactory.getLogger(EnrollLauncher.class);

    /** session 既作命令行参数又作目录名,收紧到安全字符集,防注入/路径穿越(UUID 课程 id 满足)。 */
    private static final Pattern SAFE_SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");

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
        EdgeProperties.Enroll cfg = props.getEnroll();
        if (!cfg.isEnabled() || isBlank(cfg.getPythonExecutable()) || isBlank(cfg.getScriptPath())) {
            throw new BusinessException(EdgeErrorCode.ENROLL_UNAVAILABLE,
                    "人脸采集编排未启用或命令未配置(hoopshake.edge.enroll.*)");
        }
        if (session == null || !SAFE_SESSION.matcher(session).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "session 非法:仅允许字母数字与 _-,长度 1~64");
        }
        if (!active.compareAndSet(null, session)) {
            throw new BusinessException(EdgeErrorCode.ENROLL_BUSY, "已有采集任务在进行中: " + active.get());
        }

        List<String> cmd = buildCommand(cfg, session, enrollCamera, seconds, sampleHz, expectedPersons);
        File workDir = isBlank(cfg.getWorkDir()) ? null : new File(cfg.getWorkDir());
        Map<String, String> env = cfg.getEnv();
        Duration timeout = cfg.getTimeout();

        OffsetDateTime startedAt = OffsetDateTime.now();
        EnrollRunStatus running = new EnrollRunStatus(session, EnrollState.RUNNING, null, "采集中", startedAt, null);
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
                                             Double seconds, Integer sampleHz, Integer expectedPersons) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getPythonExecutable());
        cmd.add(cfg.getScriptPath());
        cmd.add("enroll");
        cmd.add("--session");
        cmd.add(session);
        cmd.add("--enroll-camera");
        cmd.add(!isBlank(enrollCamera) ? enrollCamera : cfg.getEnrollCamera());
        cmd.add("--seconds");
        cmd.add(String.valueOf(seconds != null ? seconds : cfg.getSeconds()));
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
