package id.co.yokke.incidentdashboard.model;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Date bounds, as a unit test rather than only inside {@code IncidentQueryIT}.
 *
 * <p>The bug this pins was a 500: the query binds the upper bound as {@code dateTo + 1 day}
 * (half-open on the next midnight), so {@code 9999-12-31} arrived at the driver as
 * {@code 10000-01-01} and Oracle refused it with {@code ORA-17268: Year value is out of range}.
 * The caller had typed a perfectly legal date.
 *
 * <p>Clamped rather than rejected, deliberately and for the same reason {@code size} is: the
 * request has one unambiguous meaning — "everything up to the end of time" — and it is served
 * exactly. A 400 here would teach the caller nothing.
 */
class IncidentFilterDateBoundsTest {

    private static IncidentFilter filterWith(LocalDate from, LocalDate to) {
        return new IncidentFilter(from, to, List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, 0, 50, null, null);
    }

    @Test
    @DisplayName("a dateTo at the end of the calendar is clamped so dateTo + 1 day still fits")
    void clampsTheUpperBound() {
        IncidentFilter filter = filterWith(null, LocalDate.of(9999, 12, 31));

        assertThat(filter.dateTo()).isEqualTo(IncidentFilter.MAX_DATE);
        assertThat(filter.dateTo().plusDays(1).getYear())
                .as("the +1 day the query binds must stay inside a year Oracle can represent")
                .isEqualTo(9999);
    }

    @Test
    @DisplayName("a dateFrom below the calendar floor is clamped")
    void clampsTheLowerBound() {
        assertThat(filterWith(LocalDate.of(-4000, 1, 1), null).dateFrom())
                .isEqualTo(IncidentFilter.MIN_DATE);
    }

    @Test
    @DisplayName("ordinary dates are passed through untouched")
    void leavesRealisticDatesAlone() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 8, 23);
        IncidentFilter filter = filterWith(from, to);

        assertThat(filter.dateFrom()).isEqualTo(from);
        assertThat(filter.dateTo()).isEqualTo(to);
    }

    @Test
    @DisplayName("null bounds stay null — an absent filter is not a clamped one")
    void keepsNullsNull() {
        IncidentFilter filter = filterWith(null, null);

        assertThat(filter.dateFrom()).isNull();
        assertThat(filter.dateTo()).isNull();
    }
}
