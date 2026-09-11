package com.cnsportiot.cloud.common;

import com.cnsportiot.contracts.common.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分页参数收敛 + Page→PageResponse 桥接。重点:size/page 边界、映射、hasNext */
class PageResponsesTest {

    // normalizeSize:默认 20、上限 100、非正回默认

    @Test void size_zeroOrNegative_fallsBackToDefault() {
        assertThat(PageResponses.normalizeSize(0)).isEqualTo(20);
        assertThat(PageResponses.normalizeSize(-1)).isEqualTo(20);
        assertThat(PageResponses.normalizeSize(Integer.MIN_VALUE)).isEqualTo(20);
    }

    @Test void size_overMax_clampedTo100() {
        assertThat(PageResponses.normalizeSize(101)).isEqualTo(100);
        assertThat(PageResponses.normalizeSize(Integer.MAX_VALUE)).isEqualTo(100);
    }

    @Test void size_withinRange_kept() {
        assertThat(PageResponses.normalizeSize(1)).isEqualTo(1);
        assertThat(PageResponses.normalizeSize(20)).isEqualTo(20);
        assertThat(PageResponses.normalizeSize(100)).isEqualTo(100);
    }

    // normalizePage:负数归 0

    @Test void page_negative_clampedToZero() {
        assertThat(PageResponses.normalizePage(-5)).isZero();
        assertThat(PageResponses.normalizePage(Integer.MIN_VALUE)).isZero();
    }

    @Test void page_nonNegative_kept() {
        assertThat(PageResponses.normalizePage(0)).isZero();
        assertThat(PageResponses.normalizePage(7)).isEqualTo(7);
    }

    // toPageable:组合收敛

    @Test void toPageable_appliesNormalization() {
        Pageable p = PageResponses.toPageable(-3, 500);
        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(100);
        assertThat(p.getSort().isSorted()).isFalse();
    }

    @Test void toPageable_withSort_carriesSort() {
        Sort sort = Sort.by("createdAt").descending();
        Pageable p = PageResponses.toPageable(0, 0, sort);
        assertThat(p.getPageSize()).isEqualTo(20);   // size 0 → 默认
        assertThat(p.getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(p.getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    // from:直接转换

    @Test void from_copiesPageMetadata() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 6);
        PageResponse<String> r = PageResponses.from(page);
        assertThat(r.items()).containsExactly("a", "b");
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.totalElements()).isEqualTo(6);
        assertThat(r.totalPages()).isEqualTo(3);
        assertThat(r.hasNext()).isTrue();
    }

    @Test void from_lastPage_hasNextFalse() {
        Page<String> page = new PageImpl<>(List.of("z"), PageRequest.of(2, 2), 5);
        PageResponse<String> r = PageResponses.from(page);
        assertThat(r.hasNext()).isFalse();
    }

    @Test void from_emptyPage() {
        Page<String> page = new PageImpl<>(List.of());
        PageResponse<String> r = PageResponses.from(page);
        assertThat(r.items()).isEmpty();
        assertThat(r.totalElements()).isZero();
        assertThat(r.hasNext()).isFalse();
    }

    // from(mapper):映射元素类型

    @Test void from_withMapper_transformsElements() {
        Page<Integer> page = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(0, 3), 3);
        PageResponse<String> r = PageResponses.from(page, i -> "#" + i);
        assertThat(r.items()).containsExactly("#1", "#2", "#3");
        assertThat(r.totalElements()).isEqualTo(3);
        assertThat(r.hasNext()).isFalse();
    }

    @Test void from_withMapper_emptyStaysEmpty() {
        Page<Integer> page = new PageImpl<>(List.of());
        PageResponse<String> r = PageResponses.from(page, i -> "#" + i);
        assertThat(r.items()).isEmpty();
    }
}
