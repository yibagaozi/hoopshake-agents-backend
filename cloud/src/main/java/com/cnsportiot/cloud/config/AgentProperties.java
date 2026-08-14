package com.cnsportiot.cloud.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 云端 Agent 系统配置。默认 {@code enabled=false} —— 缺 GLM key 的开发机照常启动,
 * 对话端点降级返回 {@code LLM_UNAVAILABLE(50310)},训练数据端点不受影响
 * (见 docs/agent/agent-system-design.md §10 降级启动)。
 *
 * <pre>
 * hoopshake:
 *   agent:
 *     enabled: false
 *     tier:
 *       fast: glm-4-flash          # 型号为占位,以实际账号可用型号为准
 *       standard: glm-4-air
 *       advanced: glm-4-plus
 *     chunk:
 *       max-tokens: 450
 *       overlap-tokens: 40
 *       min-chunk-chars: 120
 *     rag:
 *       top-k: 4
 *       similarity-threshold: 0.5
 *       max-injected-open: 2       # STUDENT_OPEN 最多注入片段数
 *       max-injected-structured: 4 # STUDENT_STRUCTURED
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.agent")
public class AgentProperties {

    /** 总开关。false 时不装配任何 LLM/向量相关 bean,对话端点降级。 */
    private boolean enabled = false;

    @NestedConfigurationProperty
    private Tier tier = new Tier();

    @NestedConfigurationProperty
    private Chunk chunk = new Chunk();

    @NestedConfigurationProperty
    private Rag rag = new Rag();

    /** 档位 → GLM 型号映射(占位,见 §5.2)。 */
    @Getter
    @Setter
    public static class Tier {
        private String fast = "glm-4-flash";
        private String standard = "glm-4-air";
        private String advanced = "glm-4-plus";
    }

    /** 切分参数(见 §8.3,随语料可调,变更后需 reindex 复现)。 */
    @Getter
    @Setter
    public static class Chunk {
        /** 兜底上限:仅当某语义块超此值才二次切。 */
        private int maxTokens = 450;
        /** 长块被二次切时的重叠;结构边界之间恒为 0。 */
        private int overlapTokens = 40;
        /** 过短碎块(如孤立口诀)并入相邻的阈值。 */
        private int minChunkChars = 120;
    }

    /** 召回参数(见 §8.5)。 */
    @Getter
    @Setter
    public static class Rag {
        private int topK = 4;
        private double similarityThreshold = 0.5;
        private int maxInjectedOpen = 2;
        private int maxInjectedStructured = 4;
    }
}
