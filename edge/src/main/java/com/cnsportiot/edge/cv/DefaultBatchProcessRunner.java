package com.cnsportiot.edge.cv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link BatchProcessRunner} 的默认实现:{@link ProcessBuilder} 起子进程,合并 stderr 到 stdout,
 * 用守护线程把输出抽到日志(前缀 {@code [batch]}),主线程 {@code waitFor} 拿退出码。
 * 超时则强杀并抛异常(由编排器判定不出云)。
 */
@Component
public class DefaultBatchProcessRunner implements BatchProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultBatchProcessRunner.class);

    @Override
    public int run(List<String> command, File workDir, Map<String, String> env, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir);
        }
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("批处理进程启动失败: " + e.getMessage(), e);
        }

        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[batch] {}", line);
                }
            } catch (IOException ignored) {
                // 流关闭即进程退出
            }
        }, "batch-drain");
        drain.setDaemon(true);
        drain.start();

        try {
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("批处理超时(" + timeout + "),已强制结束");
                }
            } else {
                p.waitFor();
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IllegalStateException("批处理被中断", e);
        }
    }
}
