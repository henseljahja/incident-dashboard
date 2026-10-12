package id.co.yokke.incidentdashboard.domain.usecase.insights;

import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Forecast;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.ForecastDay;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.ForecastHistory;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.WeekdayFactor;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Daily ticket-volume forecast: weekday-seasonal level + linear trend, with an empirical
 * 95% band and a rolling-origin backtest.
 *
 * <h2>Model</h2>
 *
 * <ol>
 *   <li><b>Seasonal factor</b> per weekday = mean count of that weekday / overall mean (1.0 when
 *       the overall mean is 0). Weekdays never seen get 1.0.</li>
 *   <li>Counts are <b>deseasonalised</b> (divided by their weekday factor); the
 *       <b>level</b> is their exponentially smoothed value (alpha 0.3).</li>
 *   <li><b>Trend</b> = OLS slope of the deseasonalised series over the last
 *       {@value #TREND_WINDOW} days.</li>
 *   <li>{@code predicted(h) = max(0, (level + trend * h) * factor(weekday))}.</li>
 *   <li>The 95% band is {@code predicted +- 1.96 * sigma}, {@code sigma} being the standard
 *       deviation of the one-step in-sample errors; the lower bound is clamped at 0.</li>
 *   <li><b>MAPE</b> comes from refitting on all but the last 7 days and scoring those 7 (days
 *       with 0 actual are skipped -- MAPE is undefined there). Under 28 days of history it is
 *       reported as {@code null}/NA instead of a number the model has not earned.</li>
 * </ol>
 *
 * <p>It is a statistical extrapolation of a small, noisy count series, and the UI says so; it
 * does not know about releases, outages or holidays.
 */
public final class ForecastModel {

    static final int TREND_WINDOW = 60;
    static final double ALPHA = 0.3;
    static final int BACKTEST_DAYS = 7;
    static final int MIN_BACKTEST_HISTORY = 28;

    private ForecastModel() {
    }

    private record Fit(double[] factors, double level, double trend, double sigma) {
    }

    /**
     * @param counts one value per calendar day, oldest first, ending on {@code lastDay}; days
     *     with no ticket MUST be present as 0 (the caller fills gaps)
     */
    public static Forecast forecast(double[] counts, LocalDate lastDay, int horizon) {
        int n = counts.length;
        LocalDate firstDay = lastDay.minusDays(n - 1L);
        Fit fit = fit(counts, firstDay);

        double mean30 = mean(counts, Math.min(30, n));
        List<ForecastDay> days = new ArrayList<>();
        double next = 0;
        boolean highRisk = false;
        for (int h = 1; h <= horizon; h++) {
            LocalDate date = lastDay.plusDays(h);
            double factor = fit.factors[date.getDayOfWeek().getValue() % 7];
            double predicted = Math.max(0, (fit.level + fit.trend * h) * factor);
            double band = 1.96 * fit.sigma;
            String risk = predicted > mean30 + 2 * fit.sigma ? "HIGH"
                    : predicted > mean30 + fit.sigma ? "MEDIUM" : "LOW";
            highRisk |= "HIGH".equals(risk);
            if (h <= 7) {
                next += predicted; // "next 7 days total" is always the first week, whatever the horizon
            }
            days.add(new ForecastDay(date.toString(), weekday(date), round2(predicted),
                    round2(Math.max(0, predicted - band)), round2(predicted + band),
                    round2(factor), risk));
        }
        double current = 0;
        for (int i = Math.max(0, n - 7); i < n; i++) {
            current += counts[i];
        }
        Double change = current == 0 ? null : round1((next - current) * 100.0 / current);

        Double mape = backtest(counts, firstDay);
        String accuracy = mape == null ? "NA" : mape < 20 ? "GOOD" : mape <= 40 ? "FAIR" : "POOR";

        List<WeekdayFactor> weekdayFactors = new ArrayList<>();
        int busiest = 0;
        int quietest = 0;
        // Index 0 = Sunday ... 6 = Saturday, the layout of the mock-up's seasonality chart.
        for (int i = 0; i < 7; i++) {
            weekdayFactors.add(new WeekdayFactor(
                    DayOfWeek.of(i == 0 ? 7 : i).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    round2(fit.factors[i])));
            if (fit.factors[i] > fit.factors[busiest]) {
                busiest = i;
            }
            if (fit.factors[i] < fit.factors[quietest]) {
                quietest = i;
            }
        }

        List<String> recommendations = new ArrayList<>();
        if (change != null && change >= 30) {
            recommendations.add("SURGE");
        } else if (change != null && change <= -30) {
            recommendations.add("DROP");
        } else {
            recommendations.add("STABLE");
        }
        if (highRisk) {
            recommendations.add("HIGH_RISK_DAY");
        }

        List<ForecastHistory> history = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            history.add(new ForecastHistory(firstDay.plusDays(i).toString(), (long) counts[i]));
        }
        return new Forecast(horizon, round1(next), round1(current), change,
                round2(mean(counts, Math.min(7, n))), round2(mean(counts, Math.min(14, n))),
                round2(mean(counts, Math.min(30, n))), round3(fit.trend), mape, accuracy,
                weekdayFactors.get(busiest).weekday(), weekdayFactors.get(quietest).weekday(),
                round2(fit.level), round2(fit.sigma), n, List.copyOf(history), List.copyOf(days),
                List.copyOf(weekdayFactors), List.copyOf(recommendations));
    }

    private static Fit fit(double[] counts, LocalDate firstDay) {
        int n = counts.length;
        double[] sum = new double[7];
        int[] seen = new int[7];
        double total = 0;
        for (int i = 0; i < n; i++) {
            int wd = firstDay.plusDays(i).getDayOfWeek().getValue() % 7;
            sum[wd] += counts[i];
            seen[wd]++;
            total += counts[i];
        }
        double overall = n == 0 ? 0 : total / n;
        double[] factors = new double[7];
        for (int wd = 0; wd < 7; wd++) {
            factors[wd] = overall == 0 || seen[wd] == 0 ? 1.0 : (sum[wd] / seen[wd]) / overall;
        }
        // A weekday that is always 0 would divide by zero when deseasonalising.
        double[] safe = new double[7];
        for (int wd = 0; wd < 7; wd++) {
            safe[wd] = Math.max(factors[wd], 0.05);
        }

        double[] deseason = new double[n];
        for (int i = 0; i < n; i++) {
            deseason[i] = counts[i] / safe[firstDay.plusDays(i).getDayOfWeek().getValue() % 7];
        }
        double level = n == 0 ? 0 : deseason[0];
        double sse = 0;
        int errors = 0;
        for (int i = 1; i < n; i++) {
            double predicted = level * factors[firstDay.plusDays(i).getDayOfWeek().getValue() % 7];
            double error = counts[i] - predicted;
            sse += error * error;
            errors++;
            level = ALPHA * deseason[i] + (1 - ALPHA) * level;
        }
        double sigma = errors > 1 ? Math.sqrt(sse / errors) : 0;

        int from = Math.max(0, n - TREND_WINDOW);
        int m = n - from;
        double slope = 0;
        if (m > 1) {
            double meanX = (m - 1) / 2.0;
            double meanY = 0;
            for (int i = from; i < n; i++) {
                meanY += deseason[i];
            }
            meanY /= m;
            double sxx = 0;
            double sxy = 0;
            for (int i = 0; i < m; i++) {
                sxx += (i - meanX) * (i - meanX);
                sxy += (i - meanX) * (deseason[from + i] - meanY);
            }
            slope = sxx == 0 ? 0 : sxy / sxx;
        }
        return new Fit(factors, level, slope, sigma);
    }

    private static Double backtest(double[] counts, LocalDate firstDay) {
        int n = counts.length;
        if (n - BACKTEST_DAYS < MIN_BACKTEST_HISTORY) {
            return null;
        }
        double[] train = java.util.Arrays.copyOfRange(counts, 0, n - BACKTEST_DAYS);
        Fit fit = fit(train, firstDay);
        double sumApe = 0;
        int scored = 0;
        for (int h = 1; h <= BACKTEST_DAYS; h++) {
            double actual = counts[n - BACKTEST_DAYS + h - 1];
            if (actual == 0) {
                continue;
            }
            LocalDate date = firstDay.plusDays(n - BACKTEST_DAYS + h - 1L);
            double predicted = Math.max(0,
                    (fit.level + fit.trend * h) * fit.factors[date.getDayOfWeek().getValue() % 7]);
            sumApe += Math.abs(actual - predicted) / actual;
            scored++;
        }
        return scored == 0 ? null : round1(sumApe * 100.0 / scored);
    }

    private static double mean(double[] values, int last) {
        if (last <= 0) {
            return 0;
        }
        double sum = 0;
        for (int i = values.length - last; i < values.length; i++) {
            sum += values[i];
        }
        return sum / last;
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
