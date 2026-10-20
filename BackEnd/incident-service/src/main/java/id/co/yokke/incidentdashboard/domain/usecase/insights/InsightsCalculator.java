package id.co.yokke.incidentdashboard.domain.usecase.insights;

import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Alert;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.AlertTicket;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.AgingRow;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Assignee;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Category;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.DailyPoint;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Home;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.InstitusiRow;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.LabelCount;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Month;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.PriorityCounts;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Rca;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.RcaRow;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.SourceRow;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Every figure on the RD 22524 dashboard that is a plain aggregate, as pure functions over
 * {@link IncidentFact}. No I/O, no clock, no SQL: the definitions below are the contract, and
 * {@code InsightsCalculatorTest} pins each one against a hand-computed fixture.
 *
 * <h2>Definitions (matching the RD mock-up captions)</h2>
 *
 * <ul>
 *   <li><b>MTTR</b> -- mean {@code aging} of CLOSED tickets with a known aging; min and max
 *       alongside.</li>
 *   <li><b>SLA compliance</b> -- closed and not breached, over closed. {@code null} when
 *       nothing is closed (never a fabricated 100%).</li>
 *   <li><b>SLA breach (open)</b> -- open and {@code breached}, using the view's own flag
 *       ({@code ELAPSED > SLA_DAYS}), so it equals the KPI row's Breached tile for open work.</li>
 *   <li><b>Closure rate</b> -- closed / total.</li>
 *   <li><b>Avg resolution</b> -- MTTR per group; <b>avg open age</b> -- mean aging of OPEN
 *       tickets; <b>range</b> -- min and max resolution (closed) days.</li>
 *   <li><b>SLA used %</b> -- {@code aging / slaDays * 100}; above 100 is overdue.</li>
 * </ul>
 */
public final class InsightsCalculator {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private InsightsCalculator() {
    }

    // ------------------------------------------------------------------- helpers

    public static PriorityCounts priorityCounts(List<IncidentFact> facts) {
        long[] counts = new long[IncidentFact.PRIORITY_LADDER.size()];
        long other = 0;
        for (IncidentFact fact : facts) {
            int index = ladderIndex(fact);
            if (index < 0) {
                other++;
            } else {
                counts[index]++;
            }
        }
        return new PriorityCounts(counts[0], counts[1], counts[2], counts[3], other);
    }

    private static int ladderIndex(IncidentFact fact) {
        for (int i = 0; i < IncidentFact.PRIORITY_LADDER.size(); i++) {
            if (fact.isPriority(IncidentFact.PRIORITY_LADDER.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Mean of the non-null agings of {@code facts}, or {@code null} when there are none. */
    static Double meanAging(List<IncidentFact> facts) {
        OptionalDouble mean = facts.stream().filter(f -> f.aging() != null)
                .mapToInt(IncidentFact::aging).average();
        return mean.isPresent() ? round1(mean.getAsDouble()) : null;
    }

    static Double pct(long part, long whole) {
        return whole == 0 ? null : round1(part * 100.0 / whole);
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String label(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // ---------------------------------------------------------------------- home

    public static Home home(List<IncidentFact> facts) {
        List<IncidentFact> closed = facts.stream().filter(IncidentFact::closed).toList();
        List<IncidentFact> closedWithAging = closed.stream().filter(f -> f.aging() != null)
                .toList();
        long onTime = closed.stream().filter(f -> !f.breached()).count();
        long late = closed.size() - onTime;
        long open = facts.size() - closed.size();
        long breachedOpen = facts.stream().filter(f -> !f.closed() && f.breached()).count();

        Integer min = closedWithAging.stream().map(IncidentFact::aging).min(Integer::compare)
                .orElse(null);
        Integer max = closedWithAging.stream().map(IncidentFact::aging).max(Integer::compare)
                .orElse(null);

        Map<java.time.LocalDate, List<IncidentFact>> byDay = new TreeMap<>();
        for (IncidentFact fact : facts) {
            byDay.computeIfAbsent(fact.created(), d -> new ArrayList<>()).add(fact);
        }
        List<DailyPoint> daily = new ArrayList<>();
        byDay.forEach((day, rows) -> daily.add(
                new DailyPoint(day.toString(), rows.size(), priorityCounts(rows))));

        return new Home(facts.size(), open, closed.size(), meanAging(closedWithAging), min, max,
                onTime, late, pct(onTime, closed.size()), breachedOpen,
                pct(closed.size(), facts.size()), meanAging(facts), priorityCounts(facts),
                List.copyOf(daily));
    }

    // ------------------------------------------------------------------- monthly

    public static List<Month> monthly(List<IncidentFact> facts) {
        Map<String, List<IncidentFact>> byMonth = new TreeMap<>();
        for (IncidentFact fact : facts) {
            byMonth.computeIfAbsent(MONTH.format(fact.created()), m -> new ArrayList<>())
                    .add(fact);
        }
        List<Month> months = new ArrayList<>();
        byMonth.forEach((period, rows) -> {
            List<IncidentFact> closed = rows.stream().filter(IncidentFact::closed).toList();
            List<IncidentFact> open = rows.stream().filter(f -> !f.closed()).toList();
            months.add(new Month(period, rows.size(), priorityCounts(rows), closed.size(),
                    open.size(), meanAging(closed), meanAging(open),
                    rows.stream().filter(IncidentFact::breached).count()));
        });
        return List.copyOf(months);
    }

    // ----------------------------------------------------------------- assignees

    /** Ranked by total desc, then name asc. Unassigned tickets are one row, {@code Unassigned}. */
    public static List<Assignee> assignees(List<IncidentFact> facts, int limit) {
        Map<String, List<IncidentFact>> byAssignee = facts.stream().collect(
                Collectors.groupingBy(f -> label(f.assignee(), "Unassigned")));
        List<Map.Entry<String, List<IncidentFact>>> ordered = byAssignee.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, List<IncidentFact>>>comparingInt(e -> -e.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(limit, 0)).toList();
        List<Assignee> rows = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, List<IncidentFact>> entry : ordered) {
            List<IncidentFact> rowsOf = entry.getValue();
            List<IncidentFact> closed = rowsOf.stream().filter(IncidentFact::closed)
                    .filter(f -> f.aging() != null).toList();
            long closedCount = rowsOf.stream().filter(IncidentFact::closed).count();
            rows.add(new Assignee(rank++, entry.getKey(), rowsOf.size(), closedCount,
                    rowsOf.size() - closedCount, priorityCounts(rowsOf), meanAging(closed),
                    closed.stream().map(IncidentFact::aging).min(Integer::compare).orElse(null),
                    closed.stream().map(IncidentFact::aging).max(Integer::compare).orElse(null),
                    pct(closedCount, rowsOf.size())));
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------ category

    public static Category category(List<IncidentFact> facts, int institusiLimit) {
        Map<String, List<IncidentFact>> bySource = new TreeMap<>(facts.stream().collect(
                Collectors.groupingBy(f -> label(f.source(), "Unclassified"))));
        List<SourceRow> sources = new ArrayList<>();
        bySource.forEach((source, rows) -> {
            long closed = rows.stream().filter(IncidentFact::closed).count();
            sources.add(new SourceRow(source, rows.size(), priorityCounts(rows), closed,
                    rows.size() - closed, meanAging(rows)));
        });
        sources.sort(Comparator.comparingLong(SourceRow::total).reversed()
                .thenComparing(SourceRow::source));

        Map<String, Long> counts = facts.stream().collect(Collectors.groupingBy(
                f -> label(f.institusi(), "Unclassified"), Collectors.counting()));
        List<InstitusiRow> institusi = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(institusiLimit, 0))
                .map(e -> new InstitusiRow(e.getKey(), e.getValue(),
                        pct(e.getValue(), facts.size())))
                .toList();
        return new Category(List.copyOf(sources), institusi, brands(facts));
    }

    /** Redmine category codes 05xx are the Brand Network family (0501 VISA, 0503 MASTERCARD ...). */
    private static final java.util.regex.Pattern BRAND_CATEGORY =
            java.util.regex.Pattern.compile("^\\s*05\\d\\d\\b\\s*-?\\s*(.*)$");

    /** Brand-network tickets by network, most tickets first; the code prefix is dropped. */
    static List<InstitusiRow> brands(List<IncidentFact> facts) {
        Map<String, Long> counts = new java.util.HashMap<>();
        long total = 0;
        for (IncidentFact f : facts) {
            if (f.category() == null) {
                continue;
            }
            java.util.regex.Matcher m = BRAND_CATEGORY.matcher(f.category());
            if (m.matches()) {
                String name = m.group(1).isBlank() ? f.category().trim() : m.group(1).trim();
                counts.merge(name, 1L, Long::sum);
                total++;
            }
        }
        final long all = total;
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new InstitusiRow(e.getKey(), e.getValue(), pct(e.getValue(), all)))
                .toList();
    }

    // --------------------------------------------------------------------- aging

    /** The longest-running OPEN tickets, oldest first, ticket number as the tie-break. */
    public static List<AgingRow> aging(List<IncidentFact> facts, int limit) {
        return facts.stream().filter(f -> !f.closed() && f.aging() != null)
                .sorted(Comparator.comparingInt(IncidentFact::aging).reversed()
                        .thenComparingLong(IncidentFact::noIncident))
                .limit(Math.max(limit, 0))
                .map(f -> new AgingRow(f.noIncident(), f.subject(), f.priority(), f.status(),
                        f.institusi(), f.assignee(), f.aging(), f.slaDays(),
                        f.slaUsedPct() == null ? null : round1(f.slaUsedPct())))
                .toList();
    }

    // ----------------------------------------------------------------------- RCA

    /**
     * Problem-management view: how many tickets carry a root cause / workaround / permanent
     * fix. {@code workaroundOnly} = has a workaround but no permanent solution -- the
     * "workaround debt" the Problem manager chases. {@code missingRootCause} lists the OPEN
     * tickets with no root cause, oldest first.
     */
    public static Rca rca(List<IncidentFact> facts, int listLimit) {
        long withRc = facts.stream().filter(IncidentFact::hasRootCause).count();
        long withWa = facts.stream().filter(IncidentFact::hasWorkaround).count();
        long permanent = facts.stream().filter(InsightsCalculator::isPermanent).count();
        long workaroundOnly = facts.stream()
                .filter(f -> f.hasWorkaround() && !isPermanent(f)).count();
        List<LabelCount> byCategory = facts.stream().collect(Collectors.groupingBy(
                        f -> label(f.category(), "Unclassified"), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new LabelCount(e.getKey(), e.getValue())).toList();
        List<RcaRow> missing = facts.stream().filter(f -> !f.closed() && !f.hasRootCause())
                .sorted(Comparator
                        .comparing(IncidentFact::aging, Comparator.nullsLast(
                                Comparator.reverseOrder()))
                        .thenComparingLong(IncidentFact::noIncident))
                .limit(Math.max(listLimit, 0))
                .map(f -> new RcaRow(f.noIncident(), f.subject(), f.status(), f.category(),
                        f.assignee(), f.aging(), f.hasRootCause(), f.hasWorkaround(),
                        f.solution(), f.closed()))
                .toList();
        return new Rca(facts.size(), facts.stream().filter(f -> !f.closed()).count(), withRc,
                withWa, permanent, workaroundOnly, pct(withRc, facts.size()),
                pct(withWa, facts.size()), pct(permanent, facts.size()), byCategory, missing);
    }

    /** {@code SOLUTION} is {@code Permanent Solution} or {@code Workaround & Pemanent Solution}. */
    static boolean isPermanent(IncidentFact fact) {
        return fact.solution() != null && fact.solution().toLowerCase(java.util.Locale.ROOT)
                .contains("pemanent")
                || fact.solution() != null && fact.solution()
                        .toLowerCase(java.util.Locale.ROOT).contains("permanent");
    }

    // -------------------------------------------------------------------- alerts

    private static final int ALERT_SAMPLE = 10;

    /**
     * Live alerts over the OPEN tickets of the filtered set. Rules are fixed and listed in
     * {@link InsightsResponse.Alert}; thresholds come from the same SLA flags as the KPI row.
     * {@code forecastSurge} is supplied by the caller (it needs the forecast model).
     */
    public static List<Alert> alerts(List<IncidentFact> facts, boolean forecastSurge) {
        List<IncidentFact> open = facts.stream().filter(f -> !f.closed()).toList();
        List<Alert> alerts = new ArrayList<>();

        List<IncidentFact> breached = open.stream().filter(IncidentFact::breached).toList();
        if (!breached.isEmpty()) {
            boolean severe = breached.stream().anyMatch(
                    f -> f.isPriority("Critical") || f.isPriority("High"));
            alerts.add(new Alert("BREACHED_OPEN", severe ? "CRITICAL" : "HIGH", breached.size(),
                    sample(breached)));
        }
        List<IncidentFact> atRisk = open.stream().filter(IncidentFact::atRisk).toList();
        if (!atRisk.isEmpty()) {
            alerts.add(new Alert("AT_RISK", "WARNING", atRisk.size(), sample(atRisk)));
        }
        List<IncidentFact> critical = open.stream()
                .filter(f -> f.isPriority("Critical") || f.isPriority("High")).toList();
        if (!critical.isEmpty()) {
            alerts.add(new Alert("CRITICAL_HIGH_OPEN", "WARNING", critical.size(),
                    sample(critical)));
        }
        List<IncidentFact> unassigned = open.stream()
                .filter(f -> f.assignee() == null || f.assignee().isBlank()).toList();
        if (!unassigned.isEmpty()) {
            alerts.add(new Alert("UNASSIGNED_OPEN", "INFO", unassigned.size(),
                    sample(unassigned)));
        }
        if (forecastSurge) {
            alerts.add(new Alert("FORECAST_SURGE", "WARNING", 0, List.of()));
        }
        return List.copyOf(alerts);
    }

    /** Most overdue first: highest SLA used %, then ticket number. */
    private static List<AlertTicket> sample(List<IncidentFact> facts) {
        Function<IncidentFact, Double> used = f -> f.slaUsedPct() == null ? -1.0 : f.slaUsedPct();
        return facts.stream()
                .sorted(Comparator.comparing(used).reversed()
                        .thenComparingLong(IncidentFact::noIncident))
                .limit(ALERT_SAMPLE)
                .map(f -> new AlertTicket(f.noIncident(), f.subject(), f.priority(),
                        f.slaUsedPct() == null ? null : round1(f.slaUsedPct())))
                .toList();
    }

    /** Convenience for callers/tests that need a quick filtered count. */
    static long count(List<IncidentFact> facts, Predicate<IncidentFact> predicate) {
        return facts.stream().filter(predicate).count();
    }

    /** Insertion-ordered label counts, for callers that already grouped. */
    static List<LabelCount> labelCounts(Map<String, Long> counts) {
        Map<String, Long> ordered = new LinkedHashMap<>(counts);
        return ordered.entrySet().stream().map(e -> new LabelCount(e.getKey(), e.getValue()))
                .toList();
    }
}
