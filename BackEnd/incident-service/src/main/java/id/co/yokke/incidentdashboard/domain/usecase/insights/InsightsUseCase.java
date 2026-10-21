package id.co.yokke.incidentdashboard.domain.usecase.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.etl.FreshnessUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.config.ParametersUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.Alert;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.AlertTicket;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse.LabelCount;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentQueryUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.query.IncidentQueryUseCase.Predicate;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Loads the filtered {@code V_INCIDENT} rows once per request and hands them to the pure
 * calculators ({@link InsightsCalculator}, {@link ForecastModel}, {@link PatternAnalyzer},
 * {@link QaEngine}, {@link TextRelevance}).
 *
 * <p>No WHERE is built here: {@link IncidentQueryUseCase#basePredicate} is the one filter
 * definition, so every widget describes the same population as the table and the KPI row. The
 * tile facets (status/sla) are not applied -- these are period views.
 *
 * <p>The row load is bounded by {@value #MAX_FACTS}; hitting the bound is logged, never silent.
 * Nothing here logs a row, a subject or a question.
 */
@Service
@Lazy
public class InsightsUseCase {

    private static final Logger log = LoggerFactory.getLogger(InsightsUseCase.class);

    private static final String VIEW = "V_INCIDENT";
    static final int MAX_FACTS = 100_000;
    static final int HISTORY_DAYS = 180;
    static final int MAX_HORIZON = 30;
    private static final int MAX_LIMIT = 50;
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final JdbcTemplate jdbc;
    private final IncidentQueryUseCase incidents;
    private final FreshnessUseCase freshness;
    private final AiNarrator narrator;
    private final boolean sendSubjects;
    private final ParametersUseCase parameters;
    private final ObjectMapper mapper = new ObjectMapper();

    public InsightsUseCase(JdbcTemplate jdbc, @Lazy IncidentQueryUseCase incidents,
            @Lazy FreshnessUseCase freshness, AiNarrator narrator,
            @Value("${incident.ai.send-subjects:false}") boolean sendSubjects,
            @Lazy ParametersUseCase parameters) {
        this.jdbc = jdbc;
        this.incidents = incidents;
        this.freshness = freshness;
        this.narrator = narrator;
        this.sendSubjects = sendSubjects;
        this.parameters = parameters;
    }

    // --------------------------------------------------------------------- facts

    List<IncidentFact> facts(IncidentFilter filter) {
        Predicate predicate = incidents.basePredicate(filter);
        List<Object> params = new ArrayList<>(predicate.params());
        params.add(MAX_FACTS);
        List<IncidentFact> facts = jdbc.query("SELECT NO_INCIDENT, SUBJECT, PRIORITY, STATUS, "
                        + "INSTITUSI, EXTERNAL_INTERNAL, ASSIGNED_TO, CATEGORY_INCIDENT, "
                        + "DATE_CREATE, STOP_AT, IS_CLOSED, SLA_BREACHED, SLA_AT_RISK, AGING_NEW, "
                        + "SLA_DAYS, SOLUTION, "
                        + "CASE WHEN DBMS_LOB.GETLENGTH(ROOTCAUSE) > 0 THEN 1 ELSE 0 END AS HAS_RC, "
                        + "CASE WHEN DBMS_LOB.GETLENGTH(WORKAROUND) > 0 THEN 1 ELSE 0 END AS HAS_WA "
                        + "FROM " + VIEW + predicate.sql()
                        + " ORDER BY DATE_CREATE ASC, NO_INCIDENT ASC FETCH FIRST ? ROWS ONLY",
                (rs, i) -> {
                    Timestamp created = rs.getTimestamp("DATE_CREATE");
                    Timestamp stop = rs.getTimestamp("STOP_AT");
                    int aging = rs.getInt("AGING_NEW");
                    Integer agingValue = rs.wasNull() ? null : aging;
                    int sla = rs.getInt("SLA_DAYS");
                    Integer slaValue = rs.wasNull() ? null : sla;
                    return new IncidentFact(rs.getLong("NO_INCIDENT"), rs.getString("SUBJECT"),
                            rs.getString("PRIORITY"), rs.getString("STATUS"),
                            rs.getString("INSTITUSI"), rs.getString("EXTERNAL_INTERNAL"),
                            rs.getString("ASSIGNED_TO"), rs.getString("CATEGORY_INCIDENT"),
                            created.toLocalDateTime().toLocalDate(),
                            stop == null ? null : stop.toLocalDateTime().toLocalDate(),
                            rs.getInt("IS_CLOSED") == 1, rs.getInt("SLA_BREACHED") == 1,
                            rs.getInt("SLA_AT_RISK") == 1, agingValue, slaValue,
                            rs.getString("SOLUTION"), rs.getInt("HAS_RC") == 1,
                            rs.getInt("HAS_WA") == 1);
                }, params.toArray());
        if (facts.size() >= MAX_FACTS) {
            log.warn("insights: row bound {} reached; aggregates cover the oldest {} tickets",
                    MAX_FACTS, MAX_FACTS);
        }
        log.debug("insights: {} facts loaded", facts.size());
        return facts;
    }

    // ------------------------------------------------------------------ aggregates

    public InsightsResponse.Home home(IncidentFilter filter) {
        return InsightsCalculator.home(facts(filter));
    }

    public List<InsightsResponse.Month> monthly(IncidentFilter filter) {
        return InsightsCalculator.monthly(facts(filter));
    }

    public List<InsightsResponse.Assignee> assignees(IncidentFilter filter, int limit) {
        return InsightsCalculator.assignees(facts(filter), bound(limit));
    }

    public InsightsResponse.Category category(IncidentFilter filter, int limit) {
        return InsightsCalculator.category(facts(filter), bound(limit),
                parameters.brandCategoryPrefix());
    }

    public List<InsightsResponse.AgingRow> aging(IncidentFilter filter, int limit) {
        return InsightsCalculator.aging(facts(filter), bound(limit));
    }

    public InsightsResponse.Rca rca(IncidentFilter filter, int limit) {
        return InsightsCalculator.rca(facts(filter), bound(limit));
    }

    // ------------------------------------------------------------------ forecast

    /**
     * Daily CREATED counts for the last {@value #HISTORY_DAYS} days, gaps filled with 0, over the
     * request's dimension filters. The request's date window is deliberately ignored: a forecast
     * needs recent history, whatever period the page is showing.
     */
    public InsightsResponse.Forecast forecast(IncidentFilter filter, Integer horizonDays) {
        int requested = horizonDays == null ? parameters.defaultHorizonDays() : horizonDays;
        int horizon = Math.min(Math.max(requested, 1), MAX_HORIZON);
        LocalDate today = LocalDate.now(JAKARTA);
        return ForecastModel.forecast(dailyCounts(filter, today), today, horizon,
                parameters.forecastParams());
    }

    double[] dailyCounts(IncidentFilter filter, LocalDate today) {
        LocalDate from = today.minusDays(HISTORY_DAYS - 1L);
        Predicate predicate = incidents.basePredicate(withWindow(filter, from, today));
        double[] counts = new double[HISTORY_DAYS];
        jdbc.query("SELECT TRUNC(DATE_CREATE) AS D, COUNT(*) AS N FROM " + VIEW + predicate.sql()
                        + " GROUP BY TRUNC(DATE_CREATE)",
                rs -> {
                    LocalDate day = rs.getTimestamp("D").toLocalDateTime().toLocalDate();
                    long index = ChronoUnit.DAYS.between(from, day);
                    if (index >= 0 && index < HISTORY_DAYS) {
                        counts[(int) index] = rs.getLong("N");
                    }
                }, predicate.params().toArray());
        return counts;
    }

    private static IncidentFilter withWindow(IncidentFilter f, LocalDate from, LocalDate to) {
        return new IncidentFilter(from, to, f.trackers(), f.stages(), f.priorities(),
                f.institusi(), f.categories(), f.hasWorkaround(), null, null, f.scope(),
                f.matrixMissing(), f.search(), f.sources(), f.assignees(), f.statusNames(), 0,
                IncidentFilter.DEFAULT_PAGE_SIZE, null, null);
    }

    // -------------------------------------------------------------------- alerts

    public List<Alert> alerts(IncidentFilter filter) {
        // Alerts are about NOW: every open ticket in scope, whatever the page's date window.
        IncidentFilter now = withWindow(filter, null, null);
        boolean surge = forecast(filter, 7).recommendations().contains("SURGE");
        List<Alert> alerts = new ArrayList<>(InsightsCalculator.alerts(facts(now), surge,
                parameters.alertRulesByCode(), parameters.alertSampleSize()));
        if (freshness.freshness().stale()) {
            alerts.add(0, new Alert("DATA_STALE", "HIGH", 0, List.of()));
        }
        return List.copyOf(alerts);
    }

    // ------------------------------------------------------------------------ AI

    public InsightsResponse.DailySummary dailySummary(IncidentFilter filter, LocalDate date,
            String language) {
        List<IncidentFact> facts = facts(withWindow(filter, null, null));
        List<IncidentFact> created = facts.stream().filter(f -> date.equals(f.created()))
                .toList();
        long closed = facts.stream().filter(f -> f.closed() && date.equals(f.closedAt())).count();
        List<IncidentFact> open = facts.stream().filter(f -> !f.closed()).toList();
        List<AlertTicket> attention = InsightsCalculator.alerts(open, false).stream()
                .filter(a -> "BREACHED_OPEN".equals(a.code())).findFirst()
                .map(a -> a.tickets().stream().limit(5).toList()).orElse(List.of());
        InsightsResponse.DailySummary summary = new InsightsResponse.DailySummary(date.toString(),
                created.size(), closed, open.size(),
                open.stream().filter(IncidentFact::breached).count(),
                InsightsCalculator.priorityCounts(created), top(created, IncidentFact::institusi),
                top(created, IncidentFact::category), attention, null, narrator.provider());
        String narrative = narrate("daily-summary", summary, language);
        return new InsightsResponse.DailySummary(summary.date(), summary.created(),
                summary.closed(), summary.openNow(), summary.breachedOpen(), summary.byPriority(),
                summary.topInstitusi(), summary.topCategories(), summary.attention(), narrative,
                narrator.provider());
    }

    public InsightsResponse.Patterns patterns(IncidentFilter filter, String language) {
        List<InsightsResponse.Pattern> patterns = PatternAnalyzer.analyze(facts(filter), 10);
        String narrative = patterns.isEmpty() ? null
                : narrate("recurring-patterns", patterns, language);
        return new InsightsResponse.Patterns(patterns, narrative, narrator.provider());
    }

    /** Lexical relevance (TF-IDF) over subject, category and institusi. */
    public List<InsightsResponse.SearchHit> search(IncidentFilter filter, String query) {
        List<IncidentFact> facts = facts(filter);
        List<String> docs = facts.stream().map(f -> String.join(" ",
                nz(f.subject()), nz(f.category()), nz(f.institusi()))).toList();
        return TextRelevance.search(docs, query, 10).stream().map(hit -> {
            IncidentFact f = facts.get(hit.index());
            return new InsightsResponse.SearchHit(f.noIncident(), f.subject(), f.priority(),
                    f.institusi(), Math.round(hit.score() * 100.0) / 100.0);
        }).toList();
    }

    public InsightsResponse.Answer ask(IncidentFilter filter, String question) {
        return QaEngine.ask(question, facts(filter));
    }

    private static List<LabelCount> top(List<IncidentFact> facts,
            java.util.function.Function<IncidentFact, String> key) {
        return facts.stream().map(key).filter(v -> v != null && !v.isBlank())
                .collect(Collectors.groupingBy(String::trim, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5).map(e -> new LabelCount(e.getKey(), e.getValue())).toList();
    }

    /** Aggregates only; ticket subjects are stripped unless the operator opted in. */
    private String narrate(String task, Object payload, String language) {
        try {
            String json = mapper.writeValueAsString(payload);
            if (!sendSubjects) {
                json = mapper.writeValueAsString(stripSubjects(mapper.readTree(json)));
            }
            return narrator.narrate(task, json, language).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode stripSubjects(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("subject");
            node.elements().forEachRemaining(InsightsUseCase::stripSubjects);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(InsightsUseCase::stripSubjects);
        }
        return node;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static int bound(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

}
