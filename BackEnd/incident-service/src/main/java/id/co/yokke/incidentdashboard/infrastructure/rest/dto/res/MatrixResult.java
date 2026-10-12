package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import java.util.List;

/**
 * {@code GET /api/metrics/matrix}: the cells plus how many rows of the same filtered population
 * have a null/blank impact OR urgency. {@code missingCount} equals the {@code totalElements} of
 * {@code GET /api/incidents} with the same filter and {@code matrixMissing=true}.
 */
public record MatrixResult(List<MatrixResponse> cells, long missingCount,
        List<String> impactOrder, List<String> urgencyOrder) {
}
