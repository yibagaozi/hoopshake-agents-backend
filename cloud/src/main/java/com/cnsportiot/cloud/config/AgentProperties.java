package com.cnsportiot.cloud.config;

import com.cnsportiot.cloud.harness.llm.Tier;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 云端 Agent 系统配置
 *
 * <p>默认 {@code enabled=false}:缺 GLM key 的开发机照常启动,对话/知识端点降级返回
 * {@code LLM_UNAVAILABLE(50310)},其余端点不受影响。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.agent")
public class AgentProperties {

    /** 总开关。false 时不装配任何 LLM/向量 bean,对话/知识端点降级 */
    private boolean enabled = false;

    /** 瞬时错误(超时/5xx/429)同模型重试次数(降级链第1层) */
    private int maxRetries = 2;

    @NestedConfigurationProperty
    private Tiers tiers = new Tiers();

    /** 主调用故障后的跨模型兜底(降级链第2层) */
    @NestedConfigurationProperty
    private ModelSpec fallback = new ModelSpec("glm-5-turbo", ModelSpec.Reasoning.OFF);

    @NestedConfigurationProperty
    private Chunk chunk = new Chunk();

    @NestedConfigurationProperty
    private Rag rag = new Rag();

    @NestedConfigurationProperty
    private Tools tools = new Tools();

    /** 按档位取模型规格。业务/网关用 {@link Tier} 枚举,不直接摸字符串 */
    public ModelSpec specForTier(Tier tier) {
        return switch (tier == null ? Tier.STANDARD : tier) {
            case FAST -> tiers.getFast();
            case STANDARD -> tiers.getStandard();
            case ADVANCED -> tiers.getAdvanced();
        };
    }

    // ---- 档位映射(5.2)----

    /** 三档 → 模型规格。默认全 glm-5.2,靠 reasoning 拉开成本 */
    @Getter
    @Setter
    public static class Tiers {
        @NestedConfigurationProperty
        private ModelSpec fast = new ModelSpec("glm-5.2", ModelSpec.Reasoning.OFF);
        @NestedConfigurationProperty
        private ModelSpec standard = new ModelSpec("glm-5.2", ModelSpec.Reasoning.HIGH);
        @NestedConfigurationProperty
        private ModelSpec advanced = new ModelSpec("glm-5.2", ModelSpec.Reasoning.MAX);
    }

    /**
     * 单次调用的模型规格:哪个模型 + 推理档。
     * {@code reasoning}:{@code off} 关思考(最省)/{@code high} 中等 / {@code max} 深度(GLM-5.2 默认)
     */
    @Getter
    @Setter
    public static class ModelSpec {
        private String model = "glm-5.2";
        private Reasoning reasoning = Reasoning.MAX;

        public ModelSpec() {}

        public ModelSpec(String model, Reasoning reasoning) {
            this.model = model;
            this.reasoning = reasoning;
        }

        /** 是否关闭思考(对应 GLM 扩展参数 reasoning.enabled=false) */
        public boolean thinkingDisabled() {
            return reasoning == Reasoning.OFF;
        }

        /** high/max → reasoning_effort 值;off 时返回 null(改走关思考参数) */
        public String reasoningEffort() {
            return switch (reasoning) {
                case HIGH -> "high";
                case MAX -> "max";
                case OFF -> null;
            };
        }

        /** 推理档。yaml 的 off/high/max 经 Spring 松散绑定映射到此(不区分大小写) */
        public enum Reasoning { OFF, HIGH, MAX }
    }

    // ---- 切分参数(8.3;变更后需 reindex 复现)----
    @Getter
    @Setter
    public static class Chunk {
        /** 兜底上限:仅当某语义块超此值才二次切 */
        private int maxTokens = 450;
        /** 长块被二次切时的重叠;结构边界之间恒为 0 */
        private int overlapTokens = 40;
        /** 过短碎片并入同标题相邻块的阈值 */
        private int minChunkChars = 120;
    }

    // ---- 召回参数(8.5)----
    @Getter
    @Setter
    public static class Rag {
        private int topK = 4;
        private double similarityThreshold = 0.5;
        /** STUDENT_OPEN 最多注入片段数 */
        private int maxInjectedOpen = 2;
        /** STUDENT_STRUCTURED 最多注入片段数 */
        private int maxInjectedStructured = 4;
    }

    @Getter
    @Setter
    public static class Tools {
        /** 对话是否向模型开放工具(function-calling)。关掉即退回纯对话 + RAG */
        private boolean exposeInChat = true;
        /**
         * 是否开放调试直调端点 {@code POST /api/student/agent/tools/{name}/invoke}(不经 LLM,确定性验证工具/闸门/审计)
         * 默认关;测试期打开。生产建议保持关闭
         */
        private boolean debugEnabled = false;
        /** PostToolUseHook 对列表型结果的封顶条数(0 为不限) */
        private int maxResultItems = 50;
    }
}