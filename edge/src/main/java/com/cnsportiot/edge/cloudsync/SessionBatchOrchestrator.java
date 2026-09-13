package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.cv.BatchProcessRunner;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下课 → 批处理 → sessionProcessed 触发编排(闭合"课后批处理闭环")。
 *
 * <p>此前 {@code SessionServiceImpl.stop()} 只停录制;算法批处理跑完写
 * {@code {session}/cloud/ingest.json} 交接文件、再由 {@code sessionProcessed} 触发
 * {@link SessionCloudPublisher} 出云——中间"跑批处理 + 发信号"这步缺失。本编排器补上:
 *
 * <ul>
 *   <li><b>下课自动</b>({@code batch.enabled && batch.autoOnStop}):{@code stop()} 收尾录制后调
 *       {@link #onSessionEnded}，非阻塞提交到 {@code batchExecutor} 跑算法,成功则发 sessionProcessed;</li>
 *   <li><b>手动跑批处理</b> {@code POST /local/session/{id}/process} → {@link #triggerProcess}(需已配命令);</li>
 *   <li><b>手动直接出云</b> {@code POST /local/session/{id}/publish} → {@link #triggerPublish}
 *       (算法已手工跑好、只想把现成交接文件推云时用,不跑批处理)。</li>
 * </ul>
 *
 * <p>发信号统一走进程内 {@link EdgeEventPublisher} 发 {@link WsEventType#SESSION_PROCESSED},
 * 与算法/mock 经 {@code /internal/cv/stream} 上行的同名事件在 {@link SessionPublishListener} 汇合,
 * 出云路径唯一。同一 session 并发触发用 {@code inFlight} 去重。
 */
@Component
public class SessionBatchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SessionBatchOrchestrator.class);

    private final EdgeProperties props;
    private final EdgeEventPublisher events;
    private final BatchProcessRunner runner;
    private final TaskExecutor batchExecutor;

    /** 正在跑批处理的会话,防下课自动 + 手动重复触发同一 session。 */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public SessionBatchOrchestrator(EdgeProperties props,
                                    EdgeEventPublisher events,
                                    BatchProcessRunner runner,
                                    @Qualifier("batchExecutor") TaskExecutor batchExecutor) {
        this.props = props;
        this.events = events;
        this.runner = runner;
        this.batchExecutor = batchExecutor;
    }

    /** 下课自动触发。非阻塞:仅在开关开启且命令已配时提交,交 batchExecutor 异步跑。 */
    public void onSessionEnded(UUID sessionId, Path dataDir) {
        EdgeProperties.Batch b = props.getBatch();
        if (!b.isEnabled() || !b.isAutoOnStop()) {
            log.debug("批处理编排未启用或未开启下课自动,跳过 session={}", sessionId);
            return;
        }
        if (b.getCommand().isEmpty()) {
            log.warn("batch.enabled=true 但 batch.command 未配置,无法自动跑批处理 session={}", sessionId);
            return;
        }
        submitBatch(sessionId, dataDir);
    }

    /** 手动:对该会话跑算法批处理并(成功后)出云。需 batch 开关开启且命令已配。 */
    public void triggerProcess(UUID sessionId) {
        EdgeProperties.Batch b = props.getBatch();
        if (!b.isEnabled()) {
            throw new BusinessException(EdgeErrorCode.BATCH_UNAVAILABLE,
                    "批处理编排未启用(hoopshake.edge.batch.enabled=false)");
        }
        if (b.getCommand().isEmpty()) {
            throw new BusinessException(EdgeErrorCode.BATCH_UNAVAILABLE,
                    "批处理命令未配置(hoopshake.edge.batch.command)");
        }
        Path dataDir = sessionDir(sessionId);
        if (!Files.isDirectory(dataDir)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话目录不存在: " + dataDir);
        }
        submitBatch(sessionId, dataDir);
    }

    /**
     * 手动:把已跑好的交接文件直接出云(发 sessionProcessed,不跑批处理)。
     * 交接文件不存在则报 {@link EdgeErrorCode#HANDOFF_MISSING},提示先完成批处理。
     */
    public void triggerPublish(UUID sessionId) {
        Path handoff = sessionDir(sessionId).resolve(props.getPublish().getHandoffRelPath());
        if (!Files.exists(handoff)) {
            throw new BusinessException(EdgeErrorCode.HANDOFF_MISSING,
                    "交接文件不存在,请先完成算法批处理: " + handoff);
        }
        emit(sessionId, "manual-publish");
    }

    // ---- 内部 ----

    private void submitBatch(UUID sessionId, Path dataDir) {
        if (!inFlight.add(sessionId)) {
            log.warn("该会话批处理已在进行,忽略重复触发 session={}", sessionId);
            return;
        }
        try {
            batchExecutor.execute(() -> {
                try {
                    runBatch(sessionId, dataDir);
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (RuntimeException e) {
            // 提交失败(如队列满)也要释放在飞标记
            inFlight.remove(sessionId);
            throw e;
        }
    }

    private void runBatch(UUID sessionId, Path dataDir) {
        EdgeProperties.Batch b = props.getBatch();
        List<String> cmd = resolveCommand(b.getCommand(), sessionId, dataDir);
        File workDir = (b.getWorkDir() == null || b.getWorkDir().isBlank()) ? null : new File(b.getWorkDir());
        log.info("会话批处理开始 session={} cmd={}", sessionId, String.join(" ", cmd));

        int exit;
        try {
            exit = runner.run(cmd, workDir, b.getEnv(), b.getTimeout());
        } catch (RuntimeException e) {
            log.error("会话批处理执行异常,不出云 session={}: {}", sessionId, e.toString());
            return;
        }

        Path handoff = dataDir.resolve(props.getPublish().getHandoffRelPath());
        boolean hasHandoff = Files.exists(handoff);
        if (exit == 0 && hasHandoff) {
            log.info("会话批处理成功,触发出云 session={}", sessionId);
            emit(sessionId, "batch");
        } else if (exit == 0) {
            log.error("会话批处理退出码 0 但缺交接文件,不出云 session={} path={}", sessionId, handoff);
        } else {
            log.error("会话批处理失败,不出云 session={} exit={} handoff={}", sessionId, exit, hasHandoff);
        }
    }

    private void emit(UUID sessionId, String source) {
        events.publish(WsEventType.SESSION_PROCESSED, new WsEvents.SessionProcessed(sessionId, source));
    }

    /** 命令行占位符替换:{sessionId} {dataDir} {rawDir} {cloudDir}。 */
    private List<String> resolveCommand(List<String> template, UUID sessionId, Path dataDir) {
        String sid = sessionId.toString();
        String dir = dataDir.toString();
        String rawDir = dataDir.resolve("raw").toString();
        String cloudDir = dataDir.resolve("cloud").toString();
        return template.stream()
                .map(a -> a
                        .replace("{sessionId}", sid)
                        .replace("{dataDir}", dir)
                        .replace("{rawDir}", rawDir)
                        .replace("{cloudDir}", cloudDir))
                .toList();
    }

    private Path sessionDir(UUID sessionId) {
        return Path.of(props.getDataRoot(), "sessions", sessionId.toString());
    }
}
