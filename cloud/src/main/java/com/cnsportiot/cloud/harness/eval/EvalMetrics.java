package com.cnsportiot.cloud.harness.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 评测的纯函数指标(离线金标集 + 在线采样复用)
 * 覆盖:分类准确率(路由/工具选择)、RAG 检索 precision@k / recall@k / MRR
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    /** 分类准确率:expected 与 actual 等长逐项比较。用于路由意图/工具选择正确率 */
    public static <T> double accuracy(List<T> expected, List<T> actual) {
        if (expected == null || actual == null || expected.isEmpty() || expected.size() != actual.size()) {
            throw new IllegalArgumentException("expected/actual 需等长且非空");
        }
        int ok = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (Objects.equals(expected.get(i), actual.get(i))) {
                ok++;
            }
        }
        return (double) ok / expected.size();
    }

    /** precision@k:前 k 个检索结果中相关的比例 */
    public static <T> double precisionAtK(List<T> retrieved, Collection<T> relevant, int k) {
        if (retrieved == null || relevant == null || k <= 0) {
            return 0.0;
        }
        Set<T> rel = new HashSet<>(relevant);
        int n = Math.min(k, retrieved.size());
        if (n == 0) {
            return 0.0;
        }
        int hit = 0;
        for (int i = 0; i < n; i++) {
            if (rel.contains(retrieved.get(i))) {
                hit++;
            }
        }
        return (double) hit / n;
    }

    /** recall@k:相关文档中被前 k 个结果命中的比例 */
    public static <T> double recallAtK(List<T> retrieved, Collection<T> relevant, int k) {
        if (retrieved == null || relevant == null || relevant.isEmpty() || k <= 0) {
            return 0.0;
        }
        Set<T> rel = new HashSet<>(relevant);
        int n = Math.min(k, retrieved.size());
        Set<T> topk = new HashSet<>(retrieved.subList(0, n));
        int hit = 0;
        for (T r : rel) {
            if (topk.contains(r)) {
                hit++;
            }
        }
        return (double) hit / rel.size();
    }

    /** MRR:第一个相关结果的倒数排名(1 起);无命中为 0。多用于单查询,批量取平均。 */
    public static <T> double reciprocalRank(List<T> retrieved, Collection<T> relevant) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        Set<T> rel = new HashSet<>(relevant);
        for (int i = 0; i < retrieved.size(); i++) {
            if (rel.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
