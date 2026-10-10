package io.github.jhonatasdefreitas.notifications.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
