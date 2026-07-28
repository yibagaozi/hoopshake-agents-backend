package com.cnsportiot.edge.process;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 外部进程启动契约。mediamtx / ffmpeg 采集 / ffmpeg 录制 / CV 共用
 *
 * @param name           进程标识,用于日志与状态表
 * @param command        完整命令行
 * @param workDir        工作目录,可为 null
 * @param env            追加到子进程的环境变量;mediamtx 靠它接收配置、python 靠它设 PYTHONUNBUFFERED
 * @param stdoutConsumer stdout 读取器;ffmpeg 的 -progress 走这里
 * @param stderrConsumer stderr 读取器;ffmpeg 日志与 blackdetect 走这里
 * @param autoRestart    非零退出是否自动重启
 * @param maxFailures    连续失败达该次数后置 FAILED,不再重启
 */
public record ProcessSpec(
        String name,
        List<String> command,
        File workDir,
        Map<String, String> env,
        Consumer<InputStream> stdoutConsumer,
        Consumer<InputStream> stderrConsumer,
        boolean autoRestart,
        int maxFailures) {

    public ProcessSpec {
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public static ProcessSpec of(String name, List<String> command) {
        return new ProcessSpec(name, command, null, null, null, null, true, 5);
    }
}

