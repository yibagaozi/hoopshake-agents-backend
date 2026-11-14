package com.cnsportiot.cloud.harness.eval;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.llm.NoopLlmGateway;
import com.cnsportiot.cloud.harness.router.Intent;
import com.cnsportiot.cloud.harness.router.RouterService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线 Agent 效果评测:路由意图分类准确率(金标集 + 阈值门禁)
 * 只测确定性规则档(llmClassify=off),不依赖 LLM/DB,可在 CI 回归
 */
class RouterEvalTest {

    /** 金标集:自然措辞的学生问句 → 期望意图(按 max-keyword-hit 规则)。 */
    private static final List<Map.Entry<String, Intent>> GOLDEN = List.of(
            Map.entry("复盘一下我最近的训练表现", Intent.TRAINING_REVIEW),
            Map.entry("我这次命中率有没有提升", Intent.TRAINING_REVIEW),
            Map.entry("看看我最近打得怎么样", Intent.TRAINING_REVIEW),
            Map.entry("帮我看下训练数据和反馈", Intent.TRAINING_REVIEW),
            Map.entry("三步上篮的动作要点", Intent.ACTION_TECHNIQUE),
            Map.entry("投篮手型怎么调整", Intent.ACTION_TECHNIQUE),
            Map.entry("运球步伐怎么练", Intent.ACTION_TECHNIQUE),
            Map.entry("怎么纠正投篮出手的错误", Intent.ACTION_TECHNIQUE),
            Map.entry("什么是正确的投篮原理", Intent.KNOWLEDGE_QA),
            Map.entry("为什么屈膝能增加力量", Intent.KNOWLEDGE_QA),
            Map.entry("训练计划如何安排比较好", Intent.KNOWLEDGE_QA),
            Map.entry("热身和拉伸的区别", Intent.KNOWLEDGE_QA),
            Map.entry("你好啊教练", Intent.SMALLTALK),
            Map.entry("谢谢你的建议", Intent.SMALLTALK),
            Map.entry("你叫什么名字", Intent.SMALLTALK),
            Map.entry("今天天气不错", Intent.GENERAL));

    private RouterService ruleOnlyRouter() {
        AgentProperties props = new AgentProperties();
        props.getRouter().setLlmClassify(false);   // 纯规则,确定性
        return new RouterService(new NoopLlmGateway(), props);
    }

    @Test
    void routingAccuracy_meetsThreshold() {
        RouterService router = ruleOnlyRouter();
        List<Intent> expected = new ArrayList<>();
        List<Intent> actual = new ArrayList<>();
        for (Map.Entry<String, Intent> g : GOLDEN) {
            expected.add(g.getValue());
            actual.add(router.route(g.getKey(), false).intent());
        }
        double acc = EvalMetrics.accuracy(expected, actual);
        // 门禁:规则档路由准确率 ≥ 0.75(文档基线 0.6,此金标集应更高);低于则视为路由退化
        assertThat(acc)
                .as("路由意图分类准确率 (actual=%s)", actual)
                .isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void anchoredForcesTrainingReview() {
        // 带 trainingSessionId(anchored)恒路由到复盘,不看文本
        assertThat(ruleOnlyRouter().route("随便问一句", true).intent()).isEqualTo(Intent.TRAINING_REVIEW);
    }
}
