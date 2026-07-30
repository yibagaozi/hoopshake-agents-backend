package com.cnsportiot.contracts.common;

import java.util.List;
import java.util.function.Function;

/** 分页出参
 *
 * @param items         当前页数据
 * @param page          页码,0 起
 * @param size          每页条数,§1.4 约定上限 100
 * @param totalElements 总条数
 * @param totalPages    总页数,总条数为 0 时为 0
 * @param hasNext       是否还有下一页
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 由查询结果构造,派生量自动算
     *
     * @param items         当前页数据
     * @param page          页码,0 起
     * @param size          每页条数
     * @param totalElements 总条数
     */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = totalPages(size, totalElements);
        return new PageResponse<>(
                items, page, size, totalElements, totalPages, page + 1 < totalPages);
    }

    /** 空页。查询无结果时用,避免各处自己拼 0/0/false */
    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0, false);
    }

    /** 元素类型转换 */
    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResponse<R>(
                items.stream().<R>map(mapper).toList(),
                page, size, totalElements, totalPages, hasNext);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** size 非正时视为不分页,总页数记 0,避免除零 */
    private static int totalPages(int size, long totalElements) {
        if (size <= 0 || totalElements <= 0) {
            return 0;
        }
        return (int) ((totalElements + size - 1) / size);
    }
}

