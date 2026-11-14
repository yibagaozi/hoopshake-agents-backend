package com.cnsportiot.edge.cv;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 一次性外部批处理进程的运行契约(区别于 {@link CvProcessManager} 的常驻督程):
 * 启动 → 等到进程结束 → 返回退出码。抽成接口是为了让 {@code SessionBatchOrchestrator}
 * 的编排逻辑脱离真实进程可测(测试注入假实现,模拟退出码与交接文件产出)。
 */
public interface BatchProcessRunner {

    /**
     * 阻塞运行到结束,返回进程退出码。
     *
     * @param command 完整命令行(占位符已由调用方替换),首元素为可执行文件
     * @param workDir 工作目录,可为 null
     * @param env     追加的环境变量,可为 null
     * @param timeout 墙钟上限;为 null / 0 / 负数表示不限时
     * @throws RuntimeException 启动失败、超时(强杀后抛)或被中断
     */
    int run(List<String> command, File workDir, Map<String, String> env, Duration timeout);
}
