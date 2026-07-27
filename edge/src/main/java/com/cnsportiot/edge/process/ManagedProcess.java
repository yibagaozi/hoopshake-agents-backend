package com.cnsportiot.edge.process;

import com.cnsportiot.edge.domain.enums.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 单个外部进程的守护:阻塞在 waitFor,非零退出按退避策略重启 */
public class ManagedProcess implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ManagedProcess.class);
    private static final long GRACEFUL_STOP_SECONDS = 5;

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

    public void stop() {
        desiredRunning = false;
        Process p = process;
        if (p == null || !p.isAlive()) {
            state.set(ProcessState.STOPPED);
            return;
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

