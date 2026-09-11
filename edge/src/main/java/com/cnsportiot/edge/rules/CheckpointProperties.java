package com.cnsportiot.edge.rules;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时规则引擎配置与检查点词表(见 docs/edge/realtime-rule-engine.md)。
 * 词表体量大,建议放独立 {@code checkpoints.yaml} 经 {@code spring.config.import} 引入,绑定到本类。
 *
 * <pre>
 * hoopshake:
 *   edge:
 *     rules:
 *       enabled: true
 *       default-cooldown-ms: 8000      # 同(学生,检查点)最小推送间隔
 *       min-confidence: 0.35           # 低于此不推
 *       max-buffer-items: 500          # 上云缓冲上限,超出丢最旧
 *       flush-interval-ms: 2000        # 批量上云间隔
 *       checkpoints:
 *         - id: ft.release.elbow
 *           action-type: free_throw
 *           phase: release
 *           metric: elbow_angle
 *           min: 160
 *           max: 180
 *           ...
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.edge.rules")
public class CheckpointProperties {

    /** 总开关。false 时不评规则、不发 cue。 */
    private boolean enabled = true;

    /** 同(学生,检查点)默认冷却(ms)。 */
    private long defaultCooldownMs = 8_000;

    /** 全局最小置信;条目可各自覆盖。 */
    private double minConfidence = 0.0;

    /** 上云缓冲条数上限,超出丢最旧(现场断网时保护内存)。 */
    private int maxBufferItems = 500;

    /** 批量上云间隔(ms),对应 flush 定时。 */
    private long flushIntervalMs = 2_000;

    private List<CheckpointRule> checkpoints = new ArrayList<>();
}
