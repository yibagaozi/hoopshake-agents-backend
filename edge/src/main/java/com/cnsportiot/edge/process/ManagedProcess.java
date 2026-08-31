package com.cnsportiot.edge.process;

import com.cnsportiot.edge.domain.enums.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 单个外部进程的守护:阻塞在 waitFor,非零退出按退避策略重启 */
public class ManagedProcess implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ManagedProcess.class);
    /**
     * 优雅停止的等待上限
     *
     * <p>10s 而不是 5s:录制进程收到 q 之后要写 Matroska 的 Cues 与 Segment 时长,
     * 一节课四路同时收尾时磁盘是抢的。宽限期不够就前功尽弃 ——
     * 被强杀的文件没有索引,播放器里既没有时长也拖不动进度条
     */
    private static final long GRACEFUL_STOP_SECONDS = 10;

    private final ProcessSpec spec;
    private final AtomicReference<ProcessState> state = new AtomicReference<>(ProcessState.STOPPED);
    private volatile Process process;
    private volatile boolean desiredRunning;
    private int consecutiveFailures;

    public ManagedProcess(ProcessSpec spec) {
        this.spec = spec;
    }

    /** 提交到 supervisor 线程池执行,线程常驻直至 stop() */
    @Override
    public void run() {
        desiredRunning = true;
        while (desiredRunning && !Thread.currentThread().isInterrupted()) {
            try {
                state.set(ProcessState.STARTING);
                process = launch();
                state.set(ProcessState.RUNNING);
                consecutiveFailures = 0;
                log.info("进程已启动: {} pid={}", spec.name(), process.pid());

                int exit = process.waitFor();
                if (!desiredRunning) {
                    break;
                }
                log.warn("进程非预期退出: {} exit={}", spec.name(), exit);
                state.set(ProcessState.FAILED);

                if (!spec.autoRestart() || ++consecutiveFailures >= spec.maxFailures()) {
                    log.error("进程放弃重启: {} 连续失败 {} 次", spec.name(), consecutiveFailures);
                    break;
                }
                Thread.sleep(backoffMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                log.error("进程启动失败: {}", spec.name(), e);
                state.set(ProcessState.FAILED);
                if (++consecutiveFailures >= spec.maxFailures()) {
                    break;
                }
                sleepQuietly(backoffMillis());
            }
        }
        state.set(ProcessState.STOPPED);
    }

    private Process launch() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.command());
        if (spec.workDir() != null) {
            pb.directory(spec.workDir());
        }
        pb.environment().putAll(spec.env());
        Process p = pb.start();
        if (spec.stdoutConsumer() != null) {
            spec.stdoutConsumer().accept(p.getInputStream());
        }
        if (spec.stderrConsumer() != null) {
            spec.stderrConsumer().accept(p.getErrorStream());
        }
        return p;
    }

    /** 1s / 2s / 4s / 8s / 16s 封顶 */
    private long backoffMillis() {
        return Math.min(1000L << Math.min(consecutiveFailures, 4), 16_000L);
    }

    /**
     * 停止进程
     *
     * <p><b>顺序是 stdin 指令 → destroy() → destroyForcibly(),缺第一步在 Windows 上等于强杀。</b>
     * {@code Process.destroy()} 在 Linux 上发 SIGTERM,ffmpeg 收到会写完文件索引再退;
     * 但在 Windows 上它映射到 {@code TerminateProcess},**没有信号这回事**,
     * 进程当场消失,来不及写 Matroska 的 Cues 和时长。
     * 而场边主机正是 Windows —— 所以录制进程必须靠 {@code stopStdin="q"} 走 ffmpeg
     * 自己的退出流程。
     */
    public void stop() {
        desiredRunning = false;
        Process p = process;
        if (p == null || !p.isAlive()) {
            state.set(ProcessState.STOPPED);
            return;
        }

        if (spec.stopStdin() != null && requestGracefulExit(p)) {
            try {
                if (p.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                    log.info("进程已优雅退出: {}", spec.name());
                    state.set(ProcessState.STOPPED);
                    return;
                }
                log.warn("进程未响应停止指令,转为 destroy: {}", spec.name());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state.set(ProcessState.STOPPING);
        p.destroy();
        try {
            if (!p.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                log.warn("进程未在宽限期内退出,强制结束: {}", spec.name());
                p.destroyForcibly();
                p.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        state.set(ProcessState.STOPPED);
    }

    /**
     * 往 stdin 写停止指令
     *
     * <p>依赖 stdin 是管道 —— {@code ProcessBuilder} 默认如此。若哪天给 ffmpeg 加了
     * {@code -nostdin},或把 stdin 重定向到 INHERIT/DISCARD,这条路会静默失效,
     * 表现就是录出来的文件又不能拖进度条了。
     *
     * @return true 表示写进去了;进程已退出或管道已断时返回 false,由调用方回落到 destroy
     */
    private boolean requestGracefulExit(Process p) {
        try {
            OutputStream stdin = p.getOutputStream();
            stdin.write(spec.stopStdin().getBytes(StandardCharsets.US_ASCII));
            stdin.write('\n');
            stdin.flush();
            return true;
        } catch (IOException e) {
            // 进程可能刚好自己退了。不算错误,继续走 destroy 兜底
            log.debug("写入停止指令失败,回落到 destroy: {} ({})", spec.name(), e.getMessage());
            return false;
        }
    }

    public ProcessState state() {
        return state.get();
    }

    public void state(ProcessState newState) {
        state.set(newState);
    }

    public String name() {
        return spec.name();
    }

    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

