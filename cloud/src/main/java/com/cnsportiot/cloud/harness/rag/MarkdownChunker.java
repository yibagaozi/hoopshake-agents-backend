package com.cnsportiot.cloud.harness.rag;

import com.cnsportiot.cloud.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题树优先(HEADING_TREE_FIRST)的 Markdown 切分器(见 §8.3)。
 *
 * <p>不依赖 Spring AI,纯 Java、无共享可变状态、可单测。步骤:
 * <ol>
 *   <li>解析 ATX 标题(# ~ ######),维护标题路径栈;正文归到"最近的标题节"。</li>
 *   <li>在<b>有正文的最小标题节</b>成块(纯标题节点不成块)。</li>
 *   <li>叶子块若超 {@code chunk.maxTokens} 才二次切,切点加 overlap;结构边界之间不重叠。</li>
 *   <li>过短块(&lt; {@code minChunkChars})并入同父的相邻块。</li>
 * </ol>
 * 每块带 {@code headingPath}(面包屑),用于上下文头与将来 checkpoint 回填。
 */
@Component
public class MarkdownChunker {

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    /** 句末切分兜底(长段二次切用)。 */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。!?！？;;\\n])");

    private final AgentProperties props;

    public MarkdownChunker(AgentProperties props) {
        this.props = props;
    }

    /** 一个"标题节"的原始归集:标题路径 + 其下正文。 */
    private record Section(List<String> headingPath, String body) {}

    public List<Chunk> chunk(String markdown) {
        List<Section> sections = mergeTooShort(splitIntoSections(stripFrontmatter(markdown)));

        int maxTokens = props.getChunk().getMaxTokens();
        List<Chunk> out = new ArrayList<>();
        int idx = 0;
        for (Section s : sections) {
            String body = s.body().strip();
            if (body.isEmpty()) {
                continue;
            }
            if (estimateTokens(body) <= maxTokens) {
                out.add(new Chunk(body, s.headingPath(), leaf(s.headingPath()), idx++));
            } else {
                for (String part : tokenSplit(body, maxTokens, props.getChunk().getOverlapTokens())) {
                    out.add(new Chunk(part, s.headingPath(), leaf(s.headingPath()), idx++));
                }
            }
        }
        return out;
    }

    // ---- 结构切:线性扫描出各标题节(全部为方法内局部状态,线程安全) ----

    private List<Section> splitIntoSections(String md) {
        List<Section> sections = new ArrayList<>();
        List<String> titles = new ArrayList<>();     // 当前标题路径
        List<Integer> levels = new ArrayList<>();     // 与 titles 平行的层级号
        StringBuilder body = new StringBuilder();

        for (String line : md.split("\n", -1)) {
            Matcher m = ATX_HEADING.matcher(line);
            if (m.matches()) {
                // 落上一节(正文可能为空,空节在成块阶段被跳过)
                sections.add(new Section(List.copyOf(titles), body.toString()));
                body.setLength(0);

                int level = m.group(1).length();
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    titles.remove(titles.size() - 1);
                }
                titles.add(m.group(2).strip());
                levels.add(level);
            } else {
                body.append(line).append('\n');
            }
        }
        sections.add(new Section(List.copyOf(titles), body.toString()));
        return sections;
    }

    // ---- 过短块并入同父相邻块 ----

    private List<Section> mergeTooShort(List<Section> sections) {
        int minChars = props.getChunk().getMinChunkChars();
        List<Section> out = new ArrayList<>();
        int lastContentIdx = -1;   // out 中最近一个有正文的节
        for (Section s : sections) {
            String body = s.body().strip();
            if (body.isEmpty()) {
                out.add(s);
                continue;
            }
            // 仅并入"同一标题路径"的碎片(如意外被空行拆开的同节续写),
            // 绝不把两个不同的带标题小节(如 ## 优先级1 与 ## 优先级2)并到一起 —— 它们是独立单元。
            if (body.length() < minChars && lastContentIdx >= 0
                    && out.get(lastContentIdx).headingPath().equals(s.headingPath())) {
                Section prev = out.get(lastContentIdx);
                out.set(lastContentIdx,
                        new Section(prev.headingPath(), prev.body().strip() + "\n" + body));
            } else {
                out.add(s);
                lastContentIdx = out.size() - 1;
            }
        }
        return out;
    }

    // ---- 长块二次切(段落 → 句子,贪心装箱 + overlap) ----

    private List<String> tokenSplit(String body, int maxTokens, int overlapTokens) {
        List<String> atoms = new ArrayList<>();
        for (String para : body.split("\n{2,}")) {
            para = para.strip();
            if (para.isEmpty()) {
                continue;
            }
            if (estimateTokens(para) <= maxTokens) {
                atoms.add(para);
            } else {
                for (String sent : SENTENCE_END.split(para)) {
                    if (!sent.isBlank()) {
                        atoms.add(sent.strip());
                    }
                }
            }
        }

        List<String> chunks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int curTokens = 0;
        for (String atom : atoms) {
            int t = estimateTokens(atom);
            if (curTokens + t > maxTokens && !cur.isEmpty()) {
                chunks.add(String.join("\n", cur));
                // overlap:保留尾部若干原子,累计到 overlapTokens
                List<String> carry = new ArrayList<>();
                int carried = 0;
                for (int i = cur.size() - 1; i >= 0 && carried < overlapTokens; i--) {
                    carry.add(0, cur.get(i));
                    carried += estimateTokens(cur.get(i));
                }
                cur = new ArrayList<>(carry);
                curTokens = carried;
            }
            cur.add(atom);
            curTokens += t;
        }
        if (!cur.isEmpty()) {
            chunks.add(String.join("\n", cur));
        }
        return chunks;
    }

    // ---- 工具 ----

    private static String leaf(List<String> headingPath) {
        return headingPath.isEmpty() ? "" : headingPath.get(headingPath.size() - 1);
    }

    /** 去掉文件头部的 YAML frontmatter(--- ... ---)。 */
    static String stripFrontmatter(String md) {
        String s = md.stripLeading();
        if (!s.startsWith("---")) {
            return md;
        }
        int end = s.indexOf("\n---", 3);
        if (end < 0) {
            return md;
        }
        int nl = s.indexOf('\n', end + 1);
        return nl < 0 ? "" : s.substring(nl + 1);
    }

    /**
     * 粗略 token 估计:CJK 字符按 1,其余非空白按 ~1/4(拉丁词)。仅用于判断是否超上限。
     */
    static int estimateTokens(String text) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }
}
