package id.co.yokke.incidentdashboard.insights;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.domain.usecase.insights.ForecastModel;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Forecast;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForecastModelTest {

    // 2026-10-12 is a Monday.
    private static final LocalDate LAST = LocalDate.of(2026, 10, 12);

    private static double[] series(int n, java.util.function.IntToDoubleFunction f) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = f.applyAsDouble(i);
        }
        return out;
    }

    @Test
    @DisplayName("a constant series forecasts the constant, with no trend and a zero-width band")
    void constantSeries() {
        Forecast f = ForecastModel.forecast(series(56, i -> 4), LAST, 7);

        assertThat(f.trendPerDay()).isZero();
        assertThat(f.nextTotal()).isEqualTo(28.0);
        assertThat(f.currentTotal()).isEqualTo(28.0);
        assertThat(f.changePct()).isEqualTo(0.0);
        assertThat(f.days()).hasSize(7).allSatisfy(d -> {
            assertThat(d.predicted()).isEqualTo(4.0);
            assertThat(d.lower()).isEqualTo(4.0);
            assertThat(d.upper()).isEqualTo(4.0);
            assertThat(d.risk()).isEqualTo("LOW");
        });
        assertThat(f.recommendations()).containsExactly("STABLE");
        assertThat(f.mapePct()).isEqualTo(0.0);
        assertThat(f.accuracy()).isEqualTo("GOOD");
        assertThat(f.history()).hasSize(56);
    }

    @Test
    @DisplayName("the forecast starts the day after the last observed day")
    void datesFollowHistory() {
        Forecast f = ForecastModel.forecast(series(40, i -> 3), LAST, 14);

        assertThat(f.days().get(0).date()).isEqualTo("2026-10-13");
        assertThat(f.days().get(0).weekday()).isEqualTo("Tue");
        assertThat(f.days()).hasSize(14);
        assertThat(f.history().get(f.history().size() - 1).date()).isEqualTo("2026-10-12");
    }

    @Test
    @DisplayName("weekday seasonality: a busy Wednesday gets a factor above 1 and is the busiest day")
    void seasonality() {
        // Wednesdays carry 6, every other day 2.
        double[] counts = series(70, i -> LAST.minusDays(69L - i).getDayOfWeek()
                == java.time.DayOfWeek.WEDNESDAY ? 6 : 2);
        Forecast f = ForecastModel.forecast(counts, LAST, 7);

        assertThat(f.busiestDay()).isEqualTo("Wed");
        assertThat(f.weekdayFactors()).hasSize(7);
        assertThat(f.weekdayFactors().stream().filter(w -> w.weekday().equals("Wed"))
                .findFirst().orElseThrow().factor()).isGreaterThan(2.0);
        // Next Wednesday (2026-10-14) must be predicted far above next Tuesday (2026-10-13).
        double tue = f.days().get(0).predicted();
        double wed = f.days().get(1).predicted();
        assertThat(wed).isGreaterThan(tue * 2);
        assertThat(f.mapePct()).isLessThan(20.0);
    }

    @Test
    @DisplayName("a rising series has a positive trend; SURGE appears exactly when change >= 30%")
    void risingSeries() {
        Forecast f = ForecastModel.forecast(series(60, i -> 1 + i / 4), LAST, 7);

        assertThat(f.trendPerDay()).isPositive();
        assertThat(f.nextTotal()).isGreaterThan(0);
        assertThat(f.recommendations().contains("SURGE"))
                .isEqualTo(f.changePct() != null && f.changePct() >= 30);
    }

    @Test
    @DisplayName("a step down is smoothed, not extrapolated to zero, and never reads as SURGE")
    void stepDown() {
        Forecast f = ForecastModel.forecast(series(60, i -> i < 53 ? 8 : 2), LAST, 7);

        assertThat(f.trendPerDay()).isNegative();
        assertThat(f.recommendations()).doesNotContain("SURGE");
        assertThat(f.nextTotal()).isGreaterThan(0);
    }

    @Test
    @DisplayName("predictions never go below zero, and short history reports no MAPE")
    void clampsAndShortHistory() {
        Forecast f = ForecastModel.forecast(series(10, i -> 9 - i), LAST, 14);

        assertThat(f.days()).allSatisfy(d -> {
            assertThat(d.predicted()).isGreaterThanOrEqualTo(0.0);
            assertThat(d.lower()).isGreaterThanOrEqualTo(0.0);
        });
        assertThat(f.mapePct()).isNull();
        assertThat(f.accuracy()).isEqualTo("NA");
    }

    @Test
    @DisplayName("an all-zero history forecasts zero with no change percentage")
    void allZero() {
        Forecast f = ForecastModel.forecast(new double[60], LAST, 7);

        assertThat(f.nextTotal()).isZero();
        assertThat(f.changePct()).isNull();
        assertThat(f.days()).allSatisfy(d -> assertThat(d.predicted()).isZero());
    }
}
