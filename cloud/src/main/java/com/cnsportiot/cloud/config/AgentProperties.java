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

    @NestedConfigurationProperty
    private Router router = new Router();

    @NestedConfigurationProperty
    private Budget budget = new Budget();

    @NestedConfigurationProperty
    private Assist assist = new Assist();

    @NestedConfigurationProperty
    private Resilience resilience = new Resilience();

    @NestedConfigurationProperty
    private Baseline baseline = new Baseline();

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

    // 路由
    @Getter
    @Setter
    public static class Router {
        /** 规则拿不准时,是否用 FAST 档做一次意图分类(关掉则直接走 GENERAL 兜底) */
        private boolean llmClassify = true;
    }

    // Token 预算

    @Getter
    @Setter
    public static class Budget {
        /** 上下文预算(token 估算口径,近似);按账号实际模型上下文窗口调 */
        private int contextTokens = 128_000;
        /** 每轮为模型输出预留的 token(裁历史时扣除) */
        private int reserveOutputTokens = 2_048;
    }

    /**
     * 请求教师协助触发判定(混合:硬门槛 + LLM 确认)
     * 达到 {@code minRounds} 轮学生提问后,才用 FAST 档 LLM 判定疑问是否仍未解决;
     * 判定为未解决时,SSE 推 {@code assist} 事件让前端展示协助按钮
     */
    @Getter
    @Setter
    public static class Assist {
        /** 是否启用协助触发判定 */
        private boolean enabled = true;
        /** 触发 LLM 判定的最小学生提问轮次 */
        private int minRounds = 3;
    }

    /**
     * LLM 调用韧性:指数退避,抖动,熔断
     */
    @Getter
    @Setter
    public static class Resilience {
        @NestedConfigurationProperty
        private BackoffCfg backoff = new BackoffCfg();
        @NestedConfigurationProperty
        private CircuitCfg circuit = new CircuitCfg();
        @NestedConfigurationProperty
        private SseCfg sse = new SseCfg();
        @NestedConfigurationProperty
        private BulkheadCfg bulkhead = new BulkheadCfg();
        @NestedConfigurationProperty
        private RateLimitCfg rateLimit = new RateLimitCfg();

        @Getter
        @Setter
        public static class BackoffCfg {
            /** 首次重试基准等待(ms);cap = min(base·2^n, max) */
            private long baseMillis = 500;
            /** 退避上限(ms) */
            private long maxMillis = 8_000;
            /** 全抖动 */
            private boolean jitter = true;
        }

        @Getter
        @Setter
        public static class CircuitCfg {
            private boolean enabled = true;
            /** 连续失败达此数即 OPEN */
            private int failureThreshold = 5;
            /** OPEN 冷却时长(ms),期间快速失败 */
            private long openMillis = 15_000;
        }

        /**
         * SSE 硬上限超时:防僵尸流。到点由容器触发 onTimeout,现有收尾逻辑收敛
         * GLM reasoning=high/max 慢,别设太短致长回答被误杀;0 表示不设
         */
        @Getter
        @Setter
        public static class SseCfg {
            private long hardTimeoutMillis = 300_000;   // 5min
        }

        /**
         * 全局 LLM 流并发上限(bulkhead/舱壁):同时进行的对话流数封顶,超限快速 42900,
         * 防一次流量尖峰把内存/线程/提供方打爆(退避+熔断的补充:限住入口并发)
         * ≤0 表示不限。学生端 + 教师端共享同一配额
         */
        @Getter
        @Setter
        public static class BulkheadCfg {
            private int maxConcurrentStreams = 32;
        }

        /**
         * 按账号对 /ask 令牌桶限流
         * 桶容量 {@code burst}(允许的突发),按 {@code refillPerMinute} 匀速回填
         */
        @Getter
        @Setter
        public static class RateLimitCfg {
            private boolean enabled = true;
            /** 桶容量:短时间内允许的最大连发提问数 */
            private int burst = 8;
            /** 每分钟回填令牌数(稳态提问速率) */
            private int refillPerMinute = 30;
        }
    }

    /**
     * 动作标准度基准
     * {@code studentId} 未配置时该工具返回提示而非报错。基准来源只由服务端配置,不由模型入参指定
     *
     * <pre>
     * hoopshake:
     *   agent:
     *     baseline:
     *       student-id: &lt;stu_03 的 studentId UUID&gt;
     *       angles-source: triangulated_3d
     *       default-tolerance-deg: 15
     *       scale-deg: 40
     *       dead-joints: [right_wrist]
     * </pre>
     */
    @Getter
    @Setter
    public static class Baseline {
        /** 基准运动员 studentId(stu_03);为空则 compare_to_reference 返回"未配置基准"提示 */
        private String studentId;
        /** 基准角度来源;与目标必须同源方可比(默认 triangulated_3d) */
        private String anglesSource = "triangulated_3d";
        /** 每关节默认容差带(度) */
        private double defaultToleranceDeg = 15.0;
        /** 归一尺度(度):s = clamp(1 - dev/scale, 0, 1) */
        private double scaleDeg = 40.0;
        /** 排除的死值关节(如 right_wrist 恒 180) */
        private java.util.List<String> deadJoints =
                new java.util.ArrayList<>(java.util.List.of("right_wrist"));
    }
}