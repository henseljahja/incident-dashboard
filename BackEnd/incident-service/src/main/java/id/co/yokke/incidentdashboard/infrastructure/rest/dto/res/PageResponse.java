package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.util.List;

/**
 * One page of results.
 *
 * <p>{@code size} is the page size ACTUALLY SERVED, not the one the caller asked for: the
 * query endpoint clamps to {@link id.co.yokke.incidentdashboard.domain.model.IncidentFilter
 * #MAX_PAGE_SIZE}, and a client that echoes its own request back has no way to discover that
 * the clamp bit.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size,
            long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(List.copyOf(content), page, size, totalElements, totalPages);
    }
}
