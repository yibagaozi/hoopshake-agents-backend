package com.cnsportiot.cloud.common;

import com.cnsportiot.contracts.common.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.function.Function;

/** Spring Data ⇄ {@link PageResponse} 的桥接,只存在于 cloud 侧 */
public final class PageResponses {

    /** §1.4:page 从 0 起,size 默认 20、上限 100 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageResponses() {
    }

    /** 直接转换,元素类型不变 */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    /** 转换并映射元素类型,供"仓储返回实体、出参要 DTO"的场景 */
    public static <T, R> PageResponse<R> from(Page<T> page, Function<? super T, ? extends R> mapper) {
        return new PageResponse<R>(
                page.getContent().stream().<R>map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    /** 把 Controller 收到的原始分页参数收敛成 {@link Pageable}  */
    public static Pageable toPageable(int page, int size) {
        return PageRequest.of(normalizePage(page), normalizeSize(size));
    }

    public static Pageable toPageable(int page, int size, Sort sort) {
        return PageRequest.of(normalizePage(page), normalizeSize(size), sort);
    }

    public static int normalizePage(int page) {
        return Math.max(page, 0);
    }

    public static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}

