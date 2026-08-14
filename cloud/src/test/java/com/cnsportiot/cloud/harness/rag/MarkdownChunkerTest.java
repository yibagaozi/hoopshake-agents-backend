package com.cnsportiot.cloud.harness.rag;

import com.cnsportiot.cloud.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分器针对两类真实语料的行为验证(见 §8.3、docs/agent/examples/knowledge)。
 * 纯单元测试,不需 Spring 上下文 / GLM。
 */
class MarkdownChunkerTest {

    private MarkdownChunker chunker() {
        return new MarkdownChunker(new AgentProperties());   // 默认 maxTokens=450 / overlap=40 / minChars=120
    }

    /** 整理稿:每个"优先级 N"块成一个独立 chunk,块间不合并。 */
    @Test
    void structuredSheet_oneChunkPerPriorityBlock() {
        String md = """
                ---
                doc_id: freethrow-priorities
                source: team
                ---

                # 原地罚篮纠错要点(8 项)

                ## 优先级 1:下肢发力缺失,动作脱节

                **错误表现:** 仅靠上肢力量推球,缺少腿部蹬伸,投篮弧度低平、射程吃力。

                **纠正核心:** 构建“从脚到指”的动力链,体会力量自下而上传导。

                **动作要领:**

                - **屈膝下蹲:** 臀部后坐,保持背部挺直,重心稳定在脚掌。
                - **蹬地发力:** 双脚前掌发力蹬地,力量沿腿、髋、核心向上传递。

                **教练口诀:** “屈膝蹬地力量升,从脚到手一条龙,先蹬后投别脱节!”

                ## 优先级 2:辅助手参与发力

                **错误表现:** 非投篮手拇指拨球或手掌前推,造成球侧旋,方向左右偏移。

                **纠正核心:** 辅助手仅起稳定作用,投篮手独立完成发力。

                **动作要领:**

                - **扶球位置:** 辅助手扶在球的侧上方,手掌空出,手指轻贴保持球平衡。
                - **保持静止:** 投篮手臂发力时,辅助手保持静止,不做任何推、拨动作。
                - **自然松开:** 随球即将离手,辅助手自然松开,不干扰球的飞行。

                **教练口诀:** “辅助手只管扶,不推不拨不乱动,出手自然就松开!”
                """;

        List<Chunk> chunks = chunker().chunk(md);

        // H1 无正文不成块;两个"优先级"块各一 chunk
        assertThat(chunks).hasSize(2);

        Chunk c1 = chunks.get(0);
        Chunk c2 = chunks.get(1);
        assertThat(c1.sectionTitle()).contains("优先级 1");
        assertThat(c2.sectionTitle()).contains("优先级 2");

        // 面包屑带 H1;上下文头前缀正确
        assertThat(c2.headingPath()).first().asString().contains("原地罚篮");
        assertThat(c2.embedText()).startsWith("原地罚篮纠错要点(8 项)" + Chunk.PATH_SEP + "优先级 2");

        // 块不串味:优先级 2 含"辅助手",不含优先级 1 的"下肢发力/屈膝下蹲"
        assertThat(c2.text()).contains("辅助手");
        assertThat(c2.text()).doesNotContain("屈膝下蹲");
    }

    /** 教科书:引言段与技术小节各成块;小节内的标注段(加粗)不单独成块。 */
    @Test
    void textbook_introAndTechniqueAreSeparateChunks() {
        String md = """
                ---
                doc_id: textbook-shooting-basics
                source: textbook
                ---

                # 投篮技术

                投篮得分是篮球比赛的最终目的,篮球所有的技、战术配合都是为了创造最佳投篮时机,提高命中率。因此,投篮是篮球比赛的关键,是攻防对抗的焦点。为摆脱防守,实现投篮得分的目的,就需要掌握多种投篮方法,具备良好的身体素质和稳定的心理素质是提高命中率的重要条件。

                ## 投篮技术与方法

                ### (一)原地投篮

                #### 1. 原地立定投篮

                这是投篮方法中最基本、最容易掌握的一种投篮方法,投篮命中率较高。

                **技术动作要点:** 以右手投篮为例,从持球基本姿势开始,右脚稍上前,收腹、重心下降举球,球托至头部右上方,肘关节和虎口对准篮圈,蹬腿、伸臂、伸展身体,连贯将球投出。

                **练习提示:** 开始不要将注意力放在投篮上,当步伐协调连贯后,再强调出手方法。
                """;

        List<Chunk> chunks = chunker().chunk(md);

        assertThat(chunks).hasSize(2);

        // 引言段:headingPath 仅 ["投篮技术"]
        Chunk intro = chunks.get(0);
        assertThat(intro.headingPath()).containsExactly("投篮技术");
        assertThat(intro.text()).contains("投篮得分是篮球比赛的最终目的");

        // 技术小节:整节一块,含两个标注段;面包屑四级
        Chunk technique = chunks.get(1);
        assertThat(technique.sectionTitle()).isEqualTo("1. 原地立定投篮");
        assertThat(technique.headingPath())
                .containsExactly("投篮技术", "投篮技术与方法", "(一)原地投篮", "1. 原地立定投篮");
        assertThat(technique.text()).contains("技术动作要点");
        assertThat(technique.text()).contains("练习提示");
        assertThat(technique.embedText()).startsWith("投篮技术" + Chunk.PATH_SEP);
    }

    /** 无标题的纯文本:单块、无面包屑头。 */
    @Test
    void plainText_singleChunkNoHeader() {
        List<Chunk> chunks = chunker().chunk("这是一段没有任何标题的普通说明文字,用于验证纯文本降级。");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEmpty();
        assertThat(chunks.get(0).embedText()).isEqualTo(chunks.get(0).text());
    }
}
