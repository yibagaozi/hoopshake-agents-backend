package com.cnsportiot.cloud.harness.tool.port;

import java.util.List;
import java.util.Set;

/**
 * 教师分析指标受控词表。基础指标固定;{@code checkpoint.<name>} / {@code action.<key>.<indicator>}
 * 走前缀放行。未知指标由 {@link #normalize} 回退到默认(通过率),工具可据 {@link #isValid} 提示可用值
 */
public final class MetricVocab {

    private MetricVocab() {}

    public static final String CHECKPOINT_PASS_RATE = "checkpoint_pass_rate";
    public static final String SAFETY_ALERT_RATE = "safety_alert_rate";
    public static final String ATTENDANCE = "attendance";

    private static final Set<String> BASE = Set.of(CHECKPOINT_PASS_RATE, SAFETY_ALERT_RATE, ATTENDANCE);

    public static boolean isValid(String metric) {
        if (metric == null || metric.isBlank()) {
            return false;
        }
        String s = metric.trim();
        return BASE.contains(s) || s.startsWith("checkpoint.") || s.startsWith("action.");
    }

    /** 合法则原样(去空白),否则回退默认通过率 */
    public static String normalize(String metric) {
        return isValid(metric) ? metric.trim() : CHECKPOINT_PASS_RATE;
    }

    /** 供工具描述/报错列出的可用值 */
    public static List<String> allowed() {
        return List.of(CHECKPOINT_PASS_RATE, SAFETY_ALERT_RATE, ATTENDANCE,
                "checkpoint.<name>", "action.<key>.<indicator>");
    }
}

