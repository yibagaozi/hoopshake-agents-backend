package com.cnsportiot.cloud.harness.router;

import com.cnsportiot.cloud.harness.llm.Tier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Director 路由决策。承载本轮对话的全部编排旋钮,供 {@code ChatServiceImpl} 直接使用,
 * 并落进 {@code chat_message.detail}
 *
 * @param intent      意图分类
 * @param agent       目标 Agent(本阶段恒 SKILL_COACH)
 * @param tier        模型档位
 * @param useRag      是否召回知识库
 * @param maxInjected 最多注入片段数(0 表示不注入)
 * @param exposeTools 是否向模型开放工具
 * @param source      决策来源:anchored(锚定训练)/ rule(规则命中)/ llm(意图分类)/ fallback(兜底)
 */
public record RouteDecision(
        Intent intent,
        AgentKind agent,
        Tier tier,
        boolean useRag,
        int maxInjected,
        boolean exposeTools,
        String source) {

    /**
     * 落 detail 用的紧凑视图
     */
    public Map<String, Object> toDetail() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", intent.name());
        m.put("agent", agent.name());
        m.put("tier", tier.name());
        m.put("source", source);
        m.put("useRag", useRag);
        m.put("exposeTools", exposeTools);
        m.put("maxInjected", maxInjected);
        return m;
    }
}

