package id.co.yokke.incidentdashboard.domain.usecase.insights;

import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.AlertTicket;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Pattern;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recurring-problem detection: tickets that share an institusi AND a category, at least
 * {@value #MIN_OCCURRENCES} times in the filtered window.
 *
 * <p>{@code recommendation} is a code the SPA translates, chosen by rule:
 * <ul>
 *   <li>{@code PERMANENT_FIX} -- some occurrences were closed on a workaround and not all have a
 *       permanent solution (the workaround debt keeps re-opening the same incident);</li>
 *   <li>{@code DOCUMENT_ROOT_CAUSE} -- most occurrences carry no root cause;</li>
 *   <li>{@code MONITOR} -- otherwise.</li>
 * </ul>
 */
public final class PatternAnalyzer {

    static final int MIN_OCCURRENCES = 3;

    private PatternAnalyzer() {
    }

    public static List<Pattern> analyze(List<IncidentFact> facts, int limit) {
        record Key(String institusi, String category) {
        }
        Map<Key, List<IncidentFact>> groups = facts.stream()
                .filter(f -> f.institusi() != null && !f.institusi().isBlank()
                        && f.category() != null && !f.category().isBlank())
                .collect(Collectors.groupingBy(f -> new Key(f.institusi().trim(),
                        f.category().trim())));
        return groups.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_OCCURRENCES)
                .sorted(Comparator
                        .<Map.Entry<Key, List<IncidentFact>>>comparingInt(e -> -e.getValue().size())
                        .thenComparing(e -> e.getKey().institusi())
                        .thenComparing(e -> e.getKey().category()))
                .limit(Math.max(limit, 0))
                .map(e -> toPattern(e.getKey().institusi(), e.getKey().category(), e.getValue()))
                .toList();
    }

    private static Pattern toPattern(String institusi, String category, List<IncidentFact> rows) {
        long workaround = rows.stream().filter(f -> f.closed() && f.hasWorkaround()).count();
        long permanent = rows.stream().filter(InsightsCalculator::isPermanent).count();
        long noRootCause = rows.stream().filter(f -> !f.hasRootCause()).count();
        String recommendation = workaround > 0 && permanent < rows.size() ? "PERMANENT_FIX"
                : noRootCause * 2 > rows.size() ? "DOCUMENT_ROOT_CAUSE" : "MONITOR";
        List<AlertTicket> samples = rows.stream()
                .sorted(Comparator.comparing(IncidentFact::created).reversed()
                        .thenComparingLong(IncidentFact::noIncident))
                .limit(3)
                .map(f -> new AlertTicket(f.noIncident(), f.subject(), f.priority(),
                        f.slaUsedPct() == null ? null : InsightsCalculator.round1(f.slaUsedPct())))
                .toList();
        return new Pattern(institusi, category, rows.size(),
                rows.stream().map(IncidentFact::created).min(Comparator.naturalOrder())
                        .orElseThrow().toString(),
                rows.stream().map(IncidentFact::created).max(Comparator.naturalOrder())
                        .orElseThrow().toString(),
                workaround, permanent,
                TextRelevance.keywords(rows.stream().map(IncidentFact::subject).toList(), 5),
                samples, recommendation);
    }
}
