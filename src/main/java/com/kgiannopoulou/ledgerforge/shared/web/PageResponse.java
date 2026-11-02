package com.kgiannopoulou.ledgerforge.shared.web;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable JSON shape for paginated responses (Spring's {@code PageImpl} JSON is not a stable API). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
