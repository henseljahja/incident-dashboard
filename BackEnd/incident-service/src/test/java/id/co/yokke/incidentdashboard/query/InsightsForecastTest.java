package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.InsightsResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.req.IncidentFilterRequest;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The forecast arithmetic and the new list-filter normalisation, neither needing a database. */
class InsightsForecastTest {

    private static InsightsResponse.Forecast forecast(double[] counts, int horizon) {
        try {
            var method = id.co.yokke.incidentdashboard.domain.usecase.query.InsightsUseCase.class
                    .getDeclaredMethod("buildForecast", LocalDate.class, double[].class, int.class);
            method.setAccessible(true);
            return (InsightsResponse.Forecast) method.invoke(null, LocalDate.of(2026, 1, 1),
                    counts, horizon);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("a perfectly linear history extrapolates exactly, with a zero-width band")
    void linearHistory() {
        double[] counts = new double[10];
        for (int i = 0; i < 10; i++) {
            counts[i] = 2 + i;
        }
        InsightsResponse.Forecast result = forecast(counts, 3);

        assertThat(result.slopePerDay()).isEqualTo(1.0);
        assertThat(result.points()).hasSize(13);
        InsightsResponse.ForecastPoint first = result.points().get(10);
        assertThat(first.actual()).isNull();
        assertThat(first.forecast()).isEqualTo(12.0);
        assertThat(first.lower()).isEqualTo(12.0);
        assertThat(first.upper()).isEqualTo(12.0);
        assertThat(first.date()).isEqualTo("2026-01-11");
    }

    @Test
    @DisplayName("a falling history never forecasts below zero")
    void clampsAtZero() {
        double[] counts = {9, 7, 5, 3, 1, 0, 0};
        InsightsResponse.Forecast result = forecast(counts, 30);

        assertThat(result.points().stream().skip(counts.length))
                .allSatisfy(p -> {
                    assertThat(p.forecast()).isGreaterThanOrEqualTo(0.0);
                    assertThat(p.lower()).isGreaterThanOrEqualTo(0.0);
                });
    }

    @Test
    @DisplayName("blank search/source/assignee normalise away and long search is truncated")
    void filterNormalisation() {
        IncidentFilter filter = new IncidentFilterRequest(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, "   ",
                Arrays.asList(" ", "External", null), List.of()).toFilter();

        assertThat(filter.search()).isNull();
        assertThat(filter.sources()).containsExactly("External");
        assertThat(filter.assignees()).isEmpty();

        IncidentFilter longSearch = new IncidentFilterRequest(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, "x".repeat(500),
                null, null).toFilter();
        assertThat(longSearch.search()).hasSize(IncidentFilter.MAX_SEARCH_LENGTH);
    }
}
