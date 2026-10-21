package id.co.yokke.incidentdashboard.insights;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.domain.usecase.insights.IncidentFact;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsCalculator;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every dashboard figure, pinned against a ten-ticket fixture whose answers are worked out by
 * hand in the comments. If a definition in {@link InsightsCalculator} changes, one of these
 * fails -- the definitions are the contract with the RD's mock-ups.
 *
 * <pre>
 *  no  prio    closed aging sla breached source    institusi assignee  created
 *  1   Low     yes    10    60  no       External  BNI       budi      2026-01-05
 *  2   Low     yes    20    60  no       External  BNI       budi      2026-01-20
 *  3   Medium  yes    9     14  no       External  QRIS      siti      2026-02-02
 *  4   High    yes    10    7   YES      Internal  BNI       siti      2026-02-10
 *  5   Low     no     30    60  no       Internal  QRIS      budi      2026-02-11
 *  6   High    no     12    7   YES      External  BNI       (none)    2026-02-12
 *  7   Critical no    2     3   no       Internal  JALIN     siti      2026-02-12
 *  8   Low     yes    22    60  no       External  BNI       budi      2026-02-20
 *  9   Medium  no     13    14  no       External  QRIS      siti      2026-02-21 (at risk)
 *  10  Low     yes    (null) 60 no       Internal  JALIN     budi      2026-02-22
 * </pre>
 */
class InsightsCalculatorTest {

    private static IncidentFact fact(long no, String priority, boolean closed, Integer aging,
            Integer sla, boolean breached, String source, String institusi, String assignee,
            String created) {
        LocalDate date = LocalDate.parse(created);
        return new IncidentFact(no, "subject " + no, priority, closed ? "Closed" : "In Progress",
                institusi, source, assignee, "Payment", date,
                closed ? date.plusDays(aging == null ? 0 : aging) : null, closed, breached,
                !closed && !breached && aging != null && sla != null && sla - aging <= 1, aging,
                sla, null, false, false);
    }

    private static final List<IncidentFact> FACTS = List.of(
            fact(1, "Low", true, 10, 60, false, "External", "BNI", "budi", "2026-01-05"),
            fact(2, "Low", true, 20, 60, false, "External", "BNI", "budi", "2026-01-20"),
            fact(3, "Medium", true, 9, 14, false, "External", "QRIS", "siti", "2026-02-02"),
            fact(4, "High", true, 10, 7, true, "Internal", "BNI", "siti", "2026-02-10"),
            fact(5, "Low", false, 30, 60, false, "Internal", "QRIS", "budi", "2026-02-11"),
            fact(6, "High", false, 12, 7, true, "External", "BNI", null, "2026-02-12"),
            fact(7, "Critical", false, 2, 3, false, "Internal", "JALIN", "siti", "2026-02-12"),
            fact(8, "Low", true, 22, 60, false, "External", "BNI", "budi", "2026-02-20"),
            fact(9, "Medium", false, 13, 14, false, "External", "QRIS", "siti", "2026-02-21"),
            fact(10, "Low", true, null, 60, false, "Internal", "JALIN", "budi", "2026-02-22"));

    @Test
    @DisplayName("home tiles: totals, MTTR with range, compliance, open breaches, closure rate")
    void home() {
        InsightsResponse.Home home = InsightsCalculator.home(FACTS);

        assertThat(home.total()).isEqualTo(10);
        // closed = #1 #2 #3 #4 #8 #10
        assertThat(home.closed()).isEqualTo(6);
        assertThat(home.open()).isEqualTo(4);
        // MTTR over closed with a known aging: (10+20+9+10+22)/5 = 14.2, range 9..22
        assertThat(home.mttrDays()).isEqualTo(14.2);
        assertThat(home.mttrMinDays()).isEqualTo(9);
        assertThat(home.mttrMaxDays()).isEqualTo(22);
        // on time = closed and not breached = 5 of 6 (only #4 late)
        assertThat(home.onTime()).isEqualTo(5);
        assertThat(home.late()).isEqualTo(1);
        assertThat(home.compliancePct()).isEqualTo(83.3);
        // open and breached: #6 only (#4 is closed)
        assertThat(home.breachedOpen()).isEqualTo(1);
        assertThat(home.closurePct()).isEqualTo(60.0);
        // avg aging over the nine with a known aging: 128 / 9 = 14.2
        assertThat(home.avgAgingDays()).isEqualTo(14.2);
        assertThat(home.byPriority()).isEqualTo(new InsightsResponse.PriorityCounts(1, 2, 2, 5, 0));
        assertThat(home.byPriority().sum()).isEqualTo(home.total());
    }

    @Test
    @DisplayName("compliance and MTTR are null, not 100% / 0, when nothing is closed")
    void homeWithNothingClosed() {
        InsightsResponse.Home home = InsightsCalculator.home(
                FACTS.stream().filter(f -> !f.closed()).toList());

        assertThat(home.compliancePct()).isNull();
        assertThat(home.mttrDays()).isNull();
        assertThat(home.mttrMinDays()).isNull();
        assertThat(InsightsCalculator.home(List.of()).closurePct()).isNull();
    }

    @Test
    @DisplayName("daily trend is per created date and sums back to the total")
    void dailyTrend() {
        InsightsResponse.Home home = InsightsCalculator.home(FACTS);

        assertThat(home.daily().stream().mapToLong(InsightsResponse.DailyPoint::total).sum())
                .isEqualTo(10);
        assertThat(home.daily()).filteredOn(d -> d.date().equals("2026-02-12"))
                .singleElement().satisfies(d -> {
                    assertThat(d.total()).isEqualTo(2);
                    assertThat(d.byPriority().high()).isEqualTo(1);
                    assertThat(d.byPriority().critical()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("monthly comparison: per creation month, with avg resolution and open age")
    void monthly() {
        List<InsightsResponse.Month> months = InsightsCalculator.monthly(FACTS);

        assertThat(months).extracting(InsightsResponse.Month::period)
                .containsExactly("2026-01", "2026-02");
        InsightsResponse.Month jan = months.get(0);
        assertThat(jan.total()).isEqualTo(2);
        assertThat(jan.closed()).isEqualTo(2);
        assertThat(jan.open()).isZero();
        assertThat(jan.avgResolutionDays()).isEqualTo(15.0);
        assertThat(jan.avgOpenAgeDays()).isNull();
        assertThat(jan.slaBreach()).isZero();

        InsightsResponse.Month feb = months.get(1);
        assertThat(feb.total()).isEqualTo(8);
        assertThat(feb.closed()).isEqualTo(4);
        assertThat(feb.open()).isEqualTo(4);
        // closed with aging: 9, 10, 22 -> 13.7 ; open: 30, 12, 2, 13 -> 14.25 -> 14.3
        assertThat(feb.avgResolutionDays()).isEqualTo(13.7);
        assertThat(feb.avgOpenAgeDays()).isEqualTo(14.3);
        assertThat(feb.slaBreach()).isEqualTo(2);
        assertThat(months.stream().mapToLong(InsightsResponse.Month::total).sum()).isEqualTo(10);
    }

    @Test
    @DisplayName("assignees: ranked by total, per-priority split, min-max range, closure rate")
    void assignees() {
        List<InsightsResponse.Assignee> rows = InsightsCalculator.assignees(FACTS, 10);

        assertThat(rows).extracting(InsightsResponse.Assignee::assignee)
                .containsExactly("budi", "siti", "Unassigned");
        InsightsResponse.Assignee budi = rows.get(0);
        assertThat(budi.rank()).isEqualTo(1);
        assertThat(budi.total()).isEqualTo(5);
        assertThat(budi.closed()).isEqualTo(4);
        assertThat(budi.open()).isEqualTo(1);
        assertThat(budi.byPriority().low()).isEqualTo(5);
        // budi's closed with aging: 10, 20, 22 -> 17.3, range 10..22; closure 4/5
        assertThat(budi.avgResolutionDays()).isEqualTo(17.3);
        assertThat(budi.minResolutionDays()).isEqualTo(10);
        assertThat(budi.maxResolutionDays()).isEqualTo(22);
        assertThat(budi.closureRatePct()).isEqualTo(80.0);
        assertThat(rows.get(2).closureRatePct()).isEqualTo(0.0);
        assertThat(rows.stream().mapToLong(InsightsResponse.Assignee::total).sum()).isEqualTo(10);
        assertThat(InsightsCalculator.assignees(FACTS, 2)).hasSize(2);
    }

    @Test
    @DisplayName("category: external vs internal table and the institusi ranking with shares")
    void category() {
        InsightsResponse.Category category = InsightsCalculator.category(FACTS, 2);

        assertThat(category.sources()).extracting(InsightsResponse.SourceRow::source)
                .containsExactly("External", "Internal");
        InsightsResponse.SourceRow external = category.sources().get(0);
        assertThat(external.total()).isEqualTo(6);
        assertThat(external.closed()).isEqualTo(4);
        assertThat(external.open()).isEqualTo(2);
        assertThat(external.byPriority().low()).isEqualTo(3);
        // external agings: 10, 20, 9, 12, 22, 13 -> 86/6 = 14.3
        assertThat(external.avgAgingDays()).isEqualTo(14.3);
        assertThat(category.institusi()).extracting(InsightsResponse.InstitusiRow::label)
                .containsExactly("BNI", "QRIS");
        assertThat(category.institusi().get(0).count()).isEqualTo(5);
        assertThat(category.institusi().get(0).sharePct()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("aging: open tickets only, oldest first, SLA used % = aging / sla * 100")
    void aging() {
        List<InsightsResponse.AgingRow> rows = InsightsCalculator.aging(FACTS, 3);

        assertThat(rows).extracting(InsightsResponse.AgingRow::noIncident)
                .containsExactly(5L, 9L, 6L);
        assertThat(rows.get(0).slaUsedPct()).isEqualTo(50.0);
        // #6: 12 / 7 = 171.4% -- above 100 means overdue
        assertThat(rows.get(2).slaUsedPct()).isEqualTo(171.4);
    }

    @Test
    @DisplayName("alerts: breached, at-risk, critical/high open and unassigned, most overdue first")
    void alerts() {
        List<InsightsResponse.Alert> alerts = InsightsCalculator.alerts(FACTS, false);

        assertThat(alerts).extracting(InsightsResponse.Alert::code).containsExactly(
                "BREACHED_OPEN", "AT_RISK", "CRITICAL_HIGH_OPEN", "UNASSIGNED_OPEN");
        InsightsResponse.Alert breached = alerts.get(0);
        assertThat(breached.severity()).isEqualTo("CRITICAL"); // a High ticket is breached
        assertThat(breached.count()).isEqualTo(1);
        assertThat(alerts.get(1).tickets()).extracting(InsightsResponse.AlertTicket::noIncident)
                .contains(9L); // 14 - 13 = 1 day left = at risk
        assertThat(alerts.get(2).count()).isEqualTo(2); // #6 High, #7 Critical
        assertThat(InsightsCalculator.alerts(FACTS, true)).extracting(
                InsightsResponse.Alert::code).contains("FORECAST_SURGE");
        assertThat(InsightsCalculator.alerts(List.of(), false)).isEmpty();
    }

    @Test
    @DisplayName("RCA: coverage of root cause, workaround and permanent solution")
    void rca() {
        LocalDate d = LocalDate.parse("2026-03-01");
        List<IncidentFact> problems = List.of(
                new IncidentFact(1, "a", "High", "Closed", "BNI", "External", "x", "Payment", d, d,
                        true, false, false, 10, 7, "Permanent Solution", true, false),
                new IncidentFact(2, "b", "High", "Closed", "BNI", "External", "x", "Payment", d, d,
                        true, false, false, 10, 7, "Workaround Solution", true, true),
                new IncidentFact(3, "c", "High", "Open", "BNI", "External", "x", "Network", d, null,
                        false, false, false, 40, 7, null, false, false),
                new IncidentFact(4, "d", "High", "Open", "BNI", "External", "x", "Network", d, null,
                        false, false, false, 5, 7, null, false, true));

        InsightsResponse.Rca rca = InsightsCalculator.rca(problems, 10);

        assertThat(rca.total()).isEqualTo(4);
        assertThat(rca.withRootCause()).isEqualTo(2);
        assertThat(rca.rootCausePct()).isEqualTo(50.0);
        assertThat(rca.withWorkaround()).isEqualTo(2);
        assertThat(rca.permanentSolution()).isEqualTo(1);
        // workaround but no permanent fix: #2 and #4
        assertThat(rca.workaroundOnly()).isEqualTo(2);
        assertThat(rca.missingRootCause()).extracting(InsightsResponse.RcaRow::noIncident)
                .containsExactly(3L, 4L);
        assertThat(rca.byCategory()).extracting(InsightsResponse.LabelCount::label)
                .containsExactly("Network", "Payment");
    }

    @Test
    @DisplayName("brand network: 05xx categories counted per network, code dropped, others ignored")
    void brandNetwork() {
        List<IncidentFact> facts = new java.util.ArrayList<>();
        String[] categories = {"0503 - MASTERCARD", "0501 - VISA", "0503 - MASTERCARD",
            "0501 - VISA", "0503 - MASTERCARD", "0502 - CUP", "1202 - BNI", null, "2301 - Pengecekan"};
        for (int i = 0; i < categories.length; i++) {
            IncidentFact base = fact(i + 1, "Low", true, 1, 60, false, "External", "BNI", "budi",
                    "2026-03-01");
            facts.add(new IncidentFact(base.noIncident(), base.subject(), base.priority(),
                    base.status(), base.institusi(), base.source(), base.assignee(),
                    categories[i], base.created(), base.closedAt(), base.closed(),
                    base.breached(), base.atRisk(), base.aging(), base.slaDays(), null, false,
                    false));
        }

        List<InsightsResponse.InstitusiRow> brands = InsightsCalculator.category(facts, 10).brands();

        assertThat(brands).extracting(InsightsResponse.InstitusiRow::label)
                .containsExactly("MASTERCARD", "VISA", "CUP");
        assertThat(brands).extracting(InsightsResponse.InstitusiRow::count)
                .containsExactly(3L, 2L, 1L);
        assertThat(brands.get(0).sharePct()).isEqualTo(50.0);
        assertThat(InsightsCalculator.category(FACTS, 2).brands()).isEmpty();
    }

    private static java.util.Map<String, id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRule>
            with(String code, boolean enabled, String severity, int minCount, Double threshold) {
        java.util.Map<String, id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRule> r =
                new java.util.LinkedHashMap<>();
        r.put(code, new id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRule(
                code, enabled, severity, minCount, threshold, true, severity, 1, threshold, null,
                null));
        return r;
    }

    @Test
    @DisplayName("alert rules: disabled never fires, min count gates, severity and sample size apply")
    void alertRulesAreAdjustable() {
        assertThat(InsightsCalculator.alerts(FACTS, false, with("AT_RISK", false, "WARNING", 1,
                null), 10)).extracting(InsightsResponse.Alert::code).doesNotContain("AT_RISK");
        // two at-risk tickets (#7, #9), so a minimum of 3 suppresses the rule
        assertThat(InsightsCalculator.alerts(FACTS, false, with("AT_RISK", true, "WARNING", 3,
                null), 10)).extracting(InsightsResponse.Alert::code).doesNotContain("AT_RISK");
        List<InsightsResponse.Alert> custom = InsightsCalculator.alerts(FACTS, false,
                with("CRITICAL_HIGH_OPEN", true, "CRITICAL", 1, null), 1);
        InsightsResponse.Alert chOpen = custom.stream()
                .filter(a -> a.code().equals("CRITICAL_HIGH_OPEN")).findFirst().orElseThrow();
        assertThat(chOpen.severity()).isEqualTo("CRITICAL");
        assertThat(chOpen.count()).isEqualTo(2);
        assertThat(chOpen.tickets()).hasSize(1); // sample size 1, count still 2
    }

    @Test
    @DisplayName("alert rules: AGING_OPEN honours its day threshold; unassigned honours min age")
    void alertThresholds() {
        // open aging: #5=30 #6=12 #7=2 #9=13
        List<InsightsResponse.Alert> aging = InsightsCalculator.alerts(FACTS, false,
                with("AGING_OPEN", true, "WARNING", 1, 13.0), 10);
        assertThat(aging).filteredOn(a -> a.code().equals("AGING_OPEN")).singleElement()
                .satisfies(a -> assertThat(a.count()).isEqualTo(2)); // #5 and #9
        // #6 is the only unassigned open ticket and is 12 days old
        assertThat(InsightsCalculator.alerts(FACTS, false,
                with("UNASSIGNED_OPEN", true, "INFO", 1, 20.0), 10))
                .extracting(InsightsResponse.Alert::code).doesNotContain("UNASSIGNED_OPEN");
        assertThat(InsightsCalculator.alerts(FACTS, false,
                with("UNASSIGNED_OPEN", true, "INFO", 1, 12.0), 10))
                .extracting(InsightsResponse.Alert::code).contains("UNASSIGNED_OPEN");
    }

    @Test
    @DisplayName("brand network prefix is configurable and matched literally")
    void brandPrefix() {
        List<IncidentFact> facts = new java.util.ArrayList<>();
        String[] categories = {"0503 - MASTERCARD", "1202 - BNI", "1202 - BNI", "1299 - X"};
        for (int i = 0; i < categories.length; i++) {
            IncidentFact b = fact(i + 1, "Low", true, 1, 60, false, "External", "BNI", "budi",
                    "2026-03-01");
            facts.add(new IncidentFact(b.noIncident(), b.subject(), b.priority(), b.status(),
                    b.institusi(), b.source(), b.assignee(), categories[i], b.created(),
                    b.closedAt(), b.closed(), b.breached(), b.atRisk(), b.aging(), b.slaDays(),
                    null, false, false));
        }
        assertThat(InsightsCalculator.category(facts, 10, "12").brands())
                .extracting(InsightsResponse.InstitusiRow::label).containsExactly("BNI", "X");
        assertThat(InsightsCalculator.category(facts, 10, "05").brands())
                .extracting(InsightsResponse.InstitusiRow::label).containsExactly("MASTERCARD");
    }
}
