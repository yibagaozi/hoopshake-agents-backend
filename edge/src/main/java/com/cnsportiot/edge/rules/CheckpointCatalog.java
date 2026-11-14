package com.cnsportiot.edge.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检查点词表:启动期从 {@link CheckpointProperties} 收集、校验、建索引,按(动作类型,相位)匹配(含 {@code "*"} 通配)。
 * 非法条目(缺 id/metric、min&gt;max、无带)启动即剔除并告警,避免运行期静默失效。
 */
@Component
public class CheckpointCatalog {

    private static final Logger log = LoggerFactory.getLogger(CheckpointCatalog.class);

    private final List<CheckpointRule> rules;

    public CheckpointCatalog(CheckpointProperties props) {
        this.rules = props.getCheckpoints().stream().filter(CheckpointCatalog::valid).toList();
        log.info("实时规则词表已加载:有效 {} / 配置 {} 条", rules.size(), props.getCheckpoints().size());
    }

    /** 匹配某动作某相位的全部检查点(精确 + 通配)。 */
    public List<CheckpointRule> match(String actionType, String phase) {
        if (actionType == null || phase == null) {
            return List.of();
        }
        return rules.stream()
                .filter(r -> matches(r.getActionType(), actionType) && matches(r.getPhase(), phase))
                .toList();
    }

    public int size() {
        return rules.size();
    }

    private static boolean matches(String pattern, String value) {
        return "*".equals(pattern) || (pattern != null && pattern.equalsIgnoreCase(value));
    }

    private static boolean valid(CheckpointRule r) {
        boolean ok = r.isEnabled()
                && notBlank(r.getId()) && notBlank(r.getMetric())
                && r.getActionType() != null && r.getPhase() != null
                && (r.getMin() != null || r.getMax() != null)
                && (r.getMin() == null || r.getMax() == null || r.getMin() <= r.getMax());
        if (!ok && r.getId() != null) {
            log.warn("忽略非法 checkpoint: {}(需 id/metric/actionType/phase 齐全,且至少一个带界、min≤max)", r.getId());
        }
        return ok;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
