package id.co.yokke.incidentdashboard.mart;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards {@code V9__close_clock_and_at_risk.sql}: the SLA clock stops when a ticket closes, and
 * At Risk means an OPEN ticket about to breach (plan 2026-09-15-stakeholder-notes-p1, US1/US2).
 *
 * <p>Each test pins ONE branch of the view's {@code STOP_AT} / {@code ELAPSED_DAYS} precedence.
 * A typo in the restated view silently changes every metric, so every branch is asserted here
 * rather than inferred from an aggregate.
 *
 * <p>Fact rows are INSERTed directly, as in {@code MartSchemaIT}: the view's read-time semantics
 * are under test, and the rows are synthetic. Dates are fixed literals where the assertion is on
 * an elapsed value, so the result does not depend on the day the suite runs.
 */
class CloseClockIT extends AbstractOracleIT {

    /** Redmine ids used below. 11 = PTR, which V9 overrides to closed; Redmine says open. */
    private static final long OPEN_STATUS = 20;
    private static final long CLOSED_STATUS = 5;
    private static final long PTR_STATUS = 11;

    private static final int MEDIUM_SLA = 14;
    private static final int HIGH_SLA = 7;
    private static final int HIGH_CUSHION = 2;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ US1: the close clock

    @Test
    @DisplayName("TM001 closed in Redmine with no cf105 row stops at CLOSED_ON, not at SYSDATE")
    void redmineClosedWithoutCloseDateStopsAtClosedOn() {
        seedDimensions();
        // AC1.1: created 26 Jul, closed 27 Jul, Medium. Before V9 this aged to SYSDATE -- the
        // 50-day false breach reported in the 14 Sep meeting.
        insert(fact(1001, CLOSED_STATUS, MEDIUM_SLA, "TIMESTAMP '2026-07-26 10:00:00'")
                .with("CLOSED_ON", "TIMESTAMP '2026-07-27 09:30:00'")
                .with("LAST_DATE_STATUS", "TIMESTAMP '2026-07-27 09:30:00'"));

        Map<String, Object> r = view(1001);
        assertThat(number(r, "IS_CLOSED")).isEqualTo(1);
        assertThat(number(r, "ELAPSED_DAYS")).isEqualTo(1);
        assertThat(number(r, "AGING_NEW")).as("AGING_NEW is ELAPSED_DAYS").isEqualTo(1);
        assertThat(r.get("CLOSE_BASIS")).isEqualTo("CLOSED_ON");
        assertThat(date(r, "STOP_AT")).isEqualTo("2026-07-27");
        assertThat(number(r, "SLA_BREACHED")).isZero();
        assertThat(number(r, "SLA_AT_RISK")).isZero();
    }

    @Test
    @DisplayName("TM002 PTR is closed by override and stops at its last status change")
    void overrideClosedStatusStopsAtLastStatusChange() {
        seedDimensions();
        // AC1.2: Redmine flags PTR is_closed = 0; the business treats it as closed.
        insert(fact(1002, PTR_STATUS, MEDIUM_SLA, "TIMESTAMP '2026-07-01 08:00:00'")
                .with("LAST_DATE_STATUS", "TIMESTAMP '2026-07-06 17:45:00'"));

        Map<String, Object> r = view(1002);
        assertThat(number(r, "REDMINE_IS_CLOSED")).as("Redmine's own flag is kept").isZero();
        assertThat(number(r, "IS_CLOSED")).as("the override wins").isEqualTo(1);
        assertThat(r.get("CLOSE_BASIS")).isEqualTo("STATUS_CHANGE");
        assertThat(date(r, "STOP_AT")).isEqualTo("2026-07-06");
        assertThat(number(r, "ELAPSED_DAYS")).isEqualTo(5);
        assertThat(r.get("STATUS_STAGE"))
                .as("closed-ness is NOT a stage change: PTR stays Blocked for the ops funnel")
                .isEqualTo("Blocked");
        assertThat(number(r, "SLA_AT_RISK")).isZero();
    }

    @Test
    @DisplayName("TM003 the cf105 close date wins over CLOSED_ON, and a late close stays breached")
    void closeDateWinsAndLateCloseStaysBreached() {
        seedDimensions();
        // AC1.3: cf105 says 20 Jul, Redmine closed it on 25 Jul. High, 7-day SLA.
        insert(fact(1003, CLOSED_STATUS, HIGH_SLA, "TIMESTAMP '2026-07-01 08:00:00'")
                .with("CLOSE_DATE", "DATE '2026-07-20'")
                .with("HAS_CLOSE_DATE_ROW", "1")
                .with("CLOSED_ON", "TIMESTAMP '2026-07-25 11:00:00'")
                .with("LAST_DATE_STATUS", "TIMESTAMP '2026-07-25 11:00:00'"));

        Map<String, Object> r = view(1003);
        assertThat(r.get("CLOSE_BASIS")).isEqualTo("CLOSE_DATE");
        assertThat(date(r, "STOP_AT")).isEqualTo("2026-07-20");
        assertThat(number(r, "ELAPSED_DAYS")).isEqualTo(19);
        assertThat(number(r, "SLA_BREACHED"))
                .as("the clock stopping does not forgive a late close")
                .isEqualTo(1);
        assertThat(r.get("SLA_BUCKET")).isEqualTo("Breached");
        assertThat(number(r, "SLA_AT_RISK")).isZero();
    }

    @Test
    @DisplayName("TM004 closed with no timestamp at all is UNKNOWN: NULL elapsed, not breached")
    void closedWithoutAnyTimestampIsUnknownNotBreached() {
        seedDimensions();
        // AC1.4: no cf105 value, no CLOSED_ON, no LAST_DATE_STATUS.
        insert(fact(1004, CLOSED_STATUS, HIGH_SLA, "SYSDATE - 400")
                .with("LAST_DATE_STATUS", "NULL"));

        Map<String, Object> r = view(1004);
        assertThat(number(r, "IS_CLOSED")).isEqualTo(1);
        assertThat(r.get("STOP_AT")).isNull();
        assertThat(r.get("CLOSE_BASIS")).isEqualTo("UNKNOWN");
        assertThat(r.get("ELAPSED_DAYS"))
                .as("unknown, not days-since-now: a 400-day false breach is the bug V9 fixes")
                .isNull();
        assertThat(number(r, "SLA_BREACHED")).isZero();
        assertThat(number(r, "SLA_AT_RISK")).isZero();
        assertThat(r.get("SLA_BUCKET")).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("TM005 a closed ticket beats the empty-cf105-row branch; an open one does not")
    void closedBeatsEmptyCloseDateRow() {
        seedDimensions();
        // AC1.5, the fixture-22878 shape: closed, cf105 row present but empty.
        insert(fact(1005, CLOSED_STATUS, MEDIUM_SLA, "TIMESTAMP '2026-07-01 14:00:00'")
                .with("HAS_CLOSE_DATE_ROW", "1")
                .with("CLOSED_ON", "TIMESTAMP '2026-07-08 15:00:00'")
                .with("LAST_DATE_STATUS", "TIMESTAMP '2026-07-08 15:00:00'"));
        // ...and the same row shape, still open: unknown elapsed, exactly as before V9.
        insert(fact(1006, OPEN_STATUS, MEDIUM_SLA, "SYSDATE - 400")
                .with("HAS_CLOSE_DATE_ROW", "1"));

        Map<String, Object> closed = view(1005);
        assertThat(closed.get("CLOSE_BASIS")).isEqualTo("CLOSED_ON");
        assertThat(number(closed, "ELAPSED_DAYS"))
                .as("the closed branch is evaluated before the empty-row branch")
                .isEqualTo(7);

        Map<String, Object> open = view(1006);
        assertThat(open.get("ELAPSED_DAYS")).as("open + empty cf105 row stays NULL").isNull();
        assertThat(open.get("CLOSE_BASIS")).isEqualTo("OPEN");
        assertThat(open.get("STOP_AT")).isNull();
        assertThat(number(open, "SLA_BREACHED")).isZero();
        assertThat(number(open, "SLA_AT_RISK")).isZero();
    }

    // ---------------------------------------------------------------- US2: At Risk redefined

    @Test
    @DisplayName("TM007 High (SLA 7, cushion 2): 4 no, 5 yes, 7 yes, 8 breached and not at risk")
    void atRiskCushionBoundaries() {
        seedDimensions();
        // TRUNC(SYSDATE) - n: an open ticket's elapsed is calendar days to today, so a
        // midnight-anchored creation date gives exactly n regardless of the time of day.
        insertOpenHigh(2004, 4);
        insertOpenHigh(2005, 5);
        insertOpenHigh(2007, 7);
        insertOpenHigh(2008, 8);

        assertThat(number(view(2004), "SLA_AT_RISK"))
                .as("AC2.2: 3 days left > cushion 2")
                .isZero();
        assertThat(number(view(2005), "SLA_AT_RISK"))
                .as("AC2.1: 2 days left <= cushion 2")
                .isEqualTo(1);
        Map<String, Object> dueToday = view(2007);
        assertThat(number(dueToday, "SLA_AT_RISK")).as("0 days left, not yet over").isEqualTo(1);
        assertThat(number(dueToday, "SLA_BREACHED")).isZero();

        Map<String, Object> over = view(2008);
        assertThat(number(over, "SLA_BREACHED")).isEqualTo(1);
        assertThat(number(over, "SLA_AT_RISK"))
                .as("a breached ticket is Breached, never also At Risk -- no double count")
                .isZero();

        for (long id : List.of(2004L, 2005L, 2007L, 2008L)) {
            assertThat(view(id).get("CLOSE_BASIS")).isEqualTo("OPEN");
        }
    }

    @Test
    @DisplayName("TM008 a closed ticket at 95% of its SLA is never at risk")
    void closedTicketIsNeverAtRisk() {
        seedDimensions();
        // AC2.3: SLA 20, closed on day 19 (95%), cushion 2 -- within the cushion by the numbers,
        // but there is nothing left to act on.
        insert(fact(3001, CLOSED_STATUS, 20, "TIMESTAMP '2026-07-01 08:00:00'")
                .with("SLA_AT_RISK_DAYS", "2")
                .with("CLOSED_ON", "TIMESTAMP '2026-07-20 08:00:00'")
                .with("LAST_DATE_STATUS", "TIMESTAMP '2026-07-20 08:00:00'"));

        Map<String, Object> r = view(3001);
        assertThat(number(r, "ELAPSED_DAYS")).isEqualTo(19);
        assertThat(((Number) r.get("SLA_RATIO")).doubleValue()).isEqualTo(0.95);
        assertThat(number(r, "SLA_BREACHED")).isZero();
        assertThat(number(r, "SLA_AT_RISK")).isZero();
        assertThat(r.get("SLA_BUCKET"))
                .as("the time-used bands still describe it; they are not the At Risk rule")
                .isEqualTo("80-100%");
    }

    // ------------------------------------------------------------------------------ fixtures

    private void insertOpenHigh(long id, int daysOld) {
        insert(fact(id, OPEN_STATUS, HIGH_SLA, "TRUNC(SYSDATE) - " + daysOld)
                .with("PRIORITY_ID", "3")
                .with("SLA_AT_RISK_DAYS", Integer.toString(HIGH_CUSHION)));
    }

    private void seedDimensions() {
        insertStatus(OPEN_STATUS, "Open", 0);
        insertStatus(CLOSED_STATUS, "Closed", 1);
        // Redmine's flag for PTR is 0. The override lives in CFG_STATUS_STAGE_MAP (V9).
        insertStatus(PTR_STATUS, "PTR", 0);
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("trackers")
                + " (ID, NAME, POSITION) VALUES (7, 'Incident', 1)");
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("enumerations")
                + " (ID, NAME, TYPE, POSITION, ACTIVE) VALUES (19, 'Medium', 'IssuePriority', 1, 1)");
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("enumerations")
                + " (ID, NAME, TYPE, POSITION, ACTIVE) VALUES (3, 'High', 'IssuePriority', 2, 1)");
    }

    private void insertStatus(long id, String name, int isClosed) {
        jdbc.update("INSERT INTO " + MirrorFixtureLoader.mirrorTable("issue_statuses")
                + " (ID, NAME, IS_CLOSED, POSITION) VALUES ("
                + id + ", '" + name + "', " + isClosed + ", 1)");
    }

    /** A fact row as raw SQL expressions, so "no row" and "an empty row" are different rows. */
    private static final class Fact {
        private final Map<String, String> columns = new LinkedHashMap<>();

        Fact with(String column, String expression) {
            columns.put(column, expression);
            return this;
        }
    }

    private static Fact fact(long id, long statusId, int slaDays, String createExpr) {
        return new Fact()
                .with("NO_INCIDENT", Long.toString(id))
                .with("TRACKER_ID", "7")
                .with("SUBJECT", "'incident " + id + "'")
                .with("DATE_CREATE", createExpr)
                .with("CREATE_MONTH", "TO_CHAR(" + createExpr + ", 'YYYYMM')")
                .with("STATUS_ID", Long.toString(statusId))
                .with("PRIORITY_ID", "19")
                .with("SLA_DAYS", Integer.toString(slaDays))
                .with("HAS_CLOSE_DATE_ROW", "0")
                .with("LAST_DATE_STATUS", createExpr)
                .with("ETL_UPDATED_AT", "SYSDATE")
                .with("SOURCE_UPDATED_ON", "SYSDATE");
    }

    private void insert(Fact fact) {
        jdbc.update("INSERT INTO INCIDENT_FACT (" + String.join(", ", fact.columns.keySet())
                + ") VALUES (" + String.join(", ", fact.columns.values()) + ")");
    }

    private Map<String, Object> view(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT IS_CLOSED, REDMINE_IS_CLOSED, STATUS_STAGE, "
                        + "TO_CHAR(STOP_AT, 'YYYY-MM-DD') AS STOP_AT, CLOSE_BASIS, ELAPSED_DAYS, "
                        + "AGING_NEW, SLA_RATIO, SLA_BREACHED, SLA_BUCKET, SLA_AT_RISK "
                        + "FROM V_INCIDENT WHERE NO_INCIDENT = ?", id);
        assertThat(rows).as("incident " + id + " must be in the view").hasSize(1);
        Map<String, Object> row = new LinkedHashMap<>();
        rows.get(0).forEach((key, value) -> row.put(key.toUpperCase(Locale.ROOT), value));
        return row;
    }

    private static String date(Map<String, Object> row, String column) {
        assertThat(row.get(column)).as(column + " is set").isNotNull();
        return (String) row.get(column);
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertThat(value).as(column + " is a number, not NULL").isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }
}
