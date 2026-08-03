package it.fleetpulse.api.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    /**
     * Converte una pagina Spring Data nella risposta paginata dell'API.
     */
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    /**
     * Converte e mappa gli elementi di una pagina nella risposta dell'API.
     */
    public static <S, T> PagedResponse<T> from(
            Page<S> page,
            Function<S, T> mapper
    ) {
        return from(page.map(mapper));
    }
}
