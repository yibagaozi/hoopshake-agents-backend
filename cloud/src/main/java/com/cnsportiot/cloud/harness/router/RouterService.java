package com.cnsportiot.cloud.harness.router;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.llm.LlmGateway;
import com.cnsportiot.cloud.harness.llm.Tier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Coach Director:把学生问题分类并给出编排旋钮(档位/RAG/工具/人设)
 * 遵循不变量二"能用规则不用 LLM": 锚定训练(带 trainingSessionId)→ 直接判 TRAINING_REVIEW;否则关键词规则命中;
 * 规则拿不准且开了 {@code router.llm-classify} → 用 FAST 档做一次意图分类;仍拿不准 GENERAL 兜底
 * 本阶段所有意图都路由到 Skill Coach
 */
@Slf4j
@Component
public class RouterService {

    /** 关键词表,按优先级排列;命中数相同时靠前者胜 */
    private static final Map<Intent, String[]> RULES = new LinkedHashMap<>();
    static {
        RULES.put(Intent.TRAINING_REVIEW, new String[]{
                "复盘", "我的", "最近", "上次", "这次", "进步", "提升", "表现", "命中率",
                "训练总结", "小结", "数据", "反馈", "练得怎", "打得怎", "怎么样"});
        RULES.put(Intent.ACTION_TECHNIQUE, new String[]{
                "怎么做", "怎么练", "要点", "动作", "姿势", "发力", "手型", "手势", "出手",
                "投篮", "上篮", "罚篮", "运球", "步伐", "跳投", "技术", "纠正", "错误"});
        RULES.put(Intent.KNOWLEDGE_QA, new String[]{
                "为什么", "原理", "规则", "什么是", "区别", "训练方法", "训练计划", "如何安排", "多久"});
        RULES.put(Intent.SMALLTALK, new String[]{
                "你好", "您好", "嗨", "哈喽", "在吗", "谢谢", "多谢", "再见", "拜拜",
                "你是谁", "你叫什么", "你的名字"});
    }

    private final LlmGateway llmGateway;
    private final AgentProperties props;

    public RouterService(LlmGateway llmGateway, AgentProperties props) {
        this.llmGateway = llmGateway;
        this.props = props;
    }

    /**
     * @param message  学生本轮问题
     * @param anchored 是否锚定了训练(带 trainingSessionId)
     */
    public RouteDecision route(String message, boolean anchored) {
        if (anchored) {
            return decide(Intent.TRAINING_REVIEW, "anchored");
        }
        String text = message == null ? "" : message.trim();

        Intent ruled = byRules(text);
        if (ruled != null) {
            return decide(ruled, "rule");
        }
        if (props.getRouter().isLlmClassify()) {
            Intent classified = byLlm(text);
            if (classified != null) {
                return decide(classified, "llm");
            }
        }
        return decide(Intent.GENERAL, "fallback");
    }

    // 规则

    private Intent byRules(String text) {
        if (text.isEmpty()) {
            return null;
        }
        Intent best = null;
        int bestHits = 0;
        for (Map.Entry<Intent, String[]> e : RULES.entrySet()) {
            int hits = 0;
            for (String kw : e.getValue()) {
                if (text.contains(kw)) {
                    hits++;
                }
            }
            if (hits > bestHits) {   // 严格大于:同分保留靠前(优先级高)的
                bestHits = hits;
                best = e.getKey();
            }
        }
        return best;   // 无任何命中 → null(转 LLM / 兜底)
    }

    // LLM 兜底分类(FAST 档,极短输出)

    private Intent byLlm(String text) {
        String system = """
                你是意图分类器。判断学生这句话属于哪一类,只输出一个大写标签,不要解释、不要标点:
                TRAINING_REVIEW=复盘自己的训练/数据/进步;
                ACTION_TECHNIQUE=问某个动作怎么做/技术要点;
                KNOWLEDGE_QA=问篮球训练原理/规则/方法等知识;
                SMALLTALK=问候/闲聊/自我介绍;
                OUT_OF_SCOPE=与篮球训练无关。""";
        try {
            Optional<String> out = llmGateway.complete(
                    new LlmGateway.CompletionRequest(system, text, Tier.FAST, 8));
            if (out.isEmpty()) {
                return null;
            }
            String label = out.get().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
            for (Intent i : Intent.values()) {
                if (label.contains(i.name())) {
                    return i;
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("意图分类调用失败,退回兜底: {}", e.toString());
            return null;
        }
    }

    // 意图 → 编排旋钮

    private RouteDecision decide(Intent intent, String source) {
        int open = props.getRag().getMaxInjectedOpen();
        int structured = props.getRag().getMaxInjectedStructured();
        return switch (intent) {
            case TRAINING_REVIEW ->
                    new RouteDecision(intent, AgentKind.SKILL_COACH, Tier.STANDARD, true, structured, true, source);
            case ACTION_TECHNIQUE ->
                    new RouteDecision(intent, AgentKind.SKILL_COACH, Tier.STANDARD, true, open, true, source);
            case KNOWLEDGE_QA ->
                    new RouteDecision(intent, AgentKind.SKILL_COACH, Tier.FAST, true, open, true, source);
            case SMALLTALK ->
                    new RouteDecision(intent, AgentKind.SKILL_COACH, Tier.FAST, false, 0, false, source);
            case OUT_OF_SCOPE ->
                    new RouteDecision(intent, AgentKind.SKILL_COACH, Tier.FAST, false, 0, false, source);
            case GENERAL ->
                    new RouteDecision(intent, AgentKind.SKILL_COACH, Tier.FAST, true, open, true, source);
        };
    }
}

