package id.co.yokke.incidentdashboard.domain.usecase.insights;

import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Answer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Rule-based question answering over the filtered facts, in Indonesian and English.
 *
 * <p>It answers a closed set of questions -- "<i>metric</i> of <i>subset</i>" -- and says so
 * when it cannot, instead of guessing. The subset is any combination of an institusi (matched
 * against the institusi values actually present), a priority, a source (external/internal) and
 * an assignee; the metric is one of {@link #METRICS}. The value is computed by
 * {@link InsightsCalculator}-grade arithmetic over the same rows the widgets use, so an answer
 * can always be reconciled with a table on screen.
 */
public final class QaEngine {

    /** Metric codes the SPA translates. */
    public static final Set<String> METRICS = Set.of("COMPLIANCE", "CLOSURE", "MTTR",
            "BREACHED_OPEN", "AVG_AGING", "OPEN", "CLOSED", "COUNT");

    private QaEngine() {
    }

    public static Answer ask(String question, List<IncidentFact> facts) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String metric = metric(q);
        if (metric == null) {
            return new Answer(false, null, null, null, 0, null);
        }

        List<Predicate<IncidentFact>> filters = new ArrayList<>();
        List<String> described = new ArrayList<>();

        facts.stream().map(IncidentFact::institusi).filter(v -> v != null && !v.isBlank())
                .distinct().filter(v -> containsWord(q, v.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length)).ifPresent(v -> {
                    filters.add(f -> v.equalsIgnoreCase(f.institusi()));
                    described.add(v);
                });
        for (String priority : IncidentFact.PRIORITY_LADDER) {
            if (containsWord(q, priority.toLowerCase(Locale.ROOT))) {
                filters.add(f -> f.isPriority(priority));
                described.add(priority);
            }
        }
        if (containsWord(q, "external") || containsWord(q, "eksternal")) {
            filters.add(f -> f.source() != null && f.source().equalsIgnoreCase("external"));
            described.add("External");
        } else if (containsWord(q, "internal")) {
            filters.add(f -> f.source() != null && f.source().equalsIgnoreCase("internal"));
            described.add("Internal");
        }
        facts.stream().map(IncidentFact::assignee).filter(v -> v != null && !v.isBlank())
                .distinct().filter(v -> q.contains(v.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length)).ifPresent(v -> {
                    filters.add(f -> v.equalsIgnoreCase(f.assignee()));
                    described.add(v);
                });

        List<IncidentFact> subset = facts.stream()
                .filter(f -> filters.stream().allMatch(p -> p.test(f))).toList();
        String scope = String.join(" / ", described);
        return switch (metric) {
            case "COMPLIANCE" -> {
                long closed = subset.stream().filter(IncidentFact::closed).count();
                long ok = subset.stream().filter(f -> f.closed() && !f.breached()).count();
                yield new Answer(true, metric, scope, closed == 0 ? null : pct(ok, closed),
                        subset.size(), "%");
            }
            case "CLOSURE" -> new Answer(true, metric, scope,
                    subset.isEmpty() ? null : pct(subset.stream().filter(IncidentFact::closed)
                            .count(), subset.size()), subset.size(), "%");
            case "MTTR" -> {
                var closed = subset.stream().filter(f -> f.closed() && f.aging() != null).toList();
                yield new Answer(true, metric, scope, mean(closed), closed.size(), "d");
            }
            case "AVG_AGING" -> new Answer(true, metric, scope, mean(subset), subset.size(), "d");
            case "BREACHED_OPEN" -> {
                long n = subset.stream().filter(f -> !f.closed() && f.breached()).count();
                yield new Answer(true, metric, scope, (double) n, n, "tickets");
            }
            case "OPEN" -> {
                long n = subset.stream().filter(f -> !f.closed()).count();
                yield new Answer(true, metric, scope, (double) n, n, "tickets");
            }
            case "CLOSED" -> {
                long n = subset.stream().filter(IncidentFact::closed).count();
                yield new Answer(true, metric, scope, (double) n, n, "tickets");
            }
            default -> new Answer(true, "COUNT", scope, (double) subset.size(), subset.size(),
                    "tickets");
        };
    }

    /** Most specific wording first, so "SLA compliance" is not read as a plain count. */
    static String metric(String q) {
        if (hasAny(q, "compliance", "kepatuhan")) {
            return "COMPLIANCE";
        }
        if (hasAny(q, "closure", "penutupan")) {
            return "CLOSURE";
        }
        if (hasAny(q, "mttr", "resolusi", "resolution", "penyelesaian")) {
            return "MTTR";
        }
        if (hasAny(q, "breach", "melewati", "terlampaui", "lewat sla", "overdue")) {
            return "BREACHED_OPEN";
        }
        if (hasAny(q, "aging", "usia", "umur")) {
            return "AVG_AGING";
        }
        if (hasAny(q, "open", "terbuka", "belum selesai")) {
            return "OPEN";
        }
        if (hasAny(q, "closed", "selesai", "ditutup", "tertutup")) {
            return "CLOSED";
        }
        if (hasAny(q, "berapa", "jumlah", "how many", "count", "total")) {
            return "COUNT";
        }
        return null;
    }

    private static boolean hasAny(String q, String... needles) {
        for (String needle : needles) {
            if (q.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String q, String word) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])"
                + java.util.regex.Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(q).find();
    }

    private static Double mean(List<IncidentFact> rows) {
        var mean = rows.stream().filter(f -> f.aging() != null).mapToInt(IncidentFact::aging)
                .average();
        return mean.isPresent() ? Math.round(mean.getAsDouble() * 10.0) / 10.0 : null;
    }

    private static double pct(long part, long whole) {
        return Math.round(part * 1000.0 / whole) / 10.0;
    }
}
