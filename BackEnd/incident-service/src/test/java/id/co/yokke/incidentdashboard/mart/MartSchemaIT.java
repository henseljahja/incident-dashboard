package id.co.yokke.incidentdashboard.mart;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards {@code V3__mart.sql}: the canonical column list, and the read-time semantics of
 * {@code V_INCIDENT}.
 *
 * <p>Fact rows are INSERTed directly here. No ETL exists yet (Task 11), and the view's
 * behaviour is what is under test — driving it through an ETL that does not exist would test
 * neither.
 */
class MartSchemaIT extends AbstractOracleIT {

    /**
     * The column list {@code V3__mart.sql} declares, transcribed once, in DDL order — plus the
     * additive {@code ALTER TABLE INCIDENT_FACT ADD} columns of later V-migrations, each marked
     * with the migration that added it (see {@code .agents/patterns/migrations.md}).
     *
     * <p>This is NOT a second source of truth: it is a tripwire. Task 11 generates the MERGE
     * from {@code USER_TAB_COLUMNS}, so a column silently added to or dropped from V3 would
     * flow through to the MERGE unnoticed and the reviewer would have no diff to look at.
     * Failing here forces the DDL edit to be deliberate.
     */
    private static final List<String> DECLARED_FACT_COLUMNS = List.of(
            "NO_INCIDENT", "TRACKER_ID", "SUBJECT", "DATE_CREATE", "CREATE_MONTH",
            "UPDATED_ON", "CLOSED_ON",
            "STATUS_ID", "PRIORITY_ID", "SLA_DAYS", "PARENT_ID", "DONE_RATIO",
            "AUTHOR_ID", "AUTHOR", "ASSIGNED_TO_ID", "ASSIGNED_TO",
            "DEVELOPER_ASSIGNED_ID", "DEVELOPER_ASSIGNED",
            "TYPE_OF_INCIDENT", "TYPE_OF_INCIDENT_ID",
            "CATEGORY_INCIDENT", "CATEGORY_INCIDENT_ID",
            "EXTERNAL_INTERNAL", "EXTERNAL_INTERNAL_ID",
            "IMPACT_SEVERITY", "IMPACT_SEVERITY_ID",
            "URGENCY_SEVERITY", "URGENCY_SEVERITY_ID",
            "INSTITUSI", "INSTITUSI_ID",
            "CLOSE_DATE", "RESOLUTION_DATE", "MIGRATION_DATE",
            "HAS_CLOSE_DATE_ROW", "HAS_RESOLUTION_ROW", "HAS_DATE_REJECT", "SOLUTION",
            "LAST_STATUS", "LAST_DATE_STATUS", "FIRST_STATUS_CHANGE_AT",
            "WORKAROUND", "ROOTCAUSE",
            "ETL_UPDATED_AT", "SOURCE_UPDATED_ON", "CV_DIGEST", "CFG_GENERATION",
            // V9: the at-risk cushion, resolved at ETL time next to SLA_DAYS.
            "SLA_AT_RISK_DAYS");

    // ------------------------------------------------------------ single source of truth

    @Test
    @DisplayName("INCIDENT_FACT's catalog column set is exactly the list V3 declares")
    void everyFactColumnAppearsInTheMergeStatement() {
        List<String> catalog = factColumns();

        // Half 1 (implementable today): the catalog is readable through the exact query Task
        // 11 will generate the MERGE from, and it matches V3's declared list EXACTLY. Both
        // directions matter -- a dropped column is a silently-never-populated report field,
        // an added one is a column the MERGE will not write.
        assertThat(catalog)
                .as("USER_TAB_COLUMNS must be readable and match V3__mart.sql exactly")
                .containsExactlyInAnyOrderElementsOf(DECLARED_FACT_COLUMNS);

        // Half 2 (Task 11): every catalog column is also written by the generated MERGE.
        // Together with half 1 this is the single-source-of-truth enforcer for the whole plan:
        // V3__mart.sql is the ONLY place the mart's columns are enumerated, and a column added
        // there cannot end up silently unwritten by the ETL.
        //
        // The MERGE is generated from USER_TAB_COLUMNS at first use, so it is an instance
        // method rather than a constant -- a hand-typed constant is exactly the drift this
        // assertion exists to prevent.
        String mergeSql = useCase(MartRefreshRepository.class).mergeSql();
        assertThat(catalog)
                .as("every INCIDENT_FACT column must appear in the generated MERGE")
                .allMatch(mergeSql::contains);
    }

    @Test
    @DisplayName("aging and SLA columns are absent from the table and present on the view")
    void agingIsComputedAtReadTimeNotStored() {
        // Materialising anything clock-derived makes every row stale the moment it is
        // written, so these must exist ONLY on the view.
        assertThat(factColumns())
                .doesNotContain("AGING_NEW", "AGING_INCIDENT", "AGING_LAST_STATUS",
                        "ELAPSED_DAYS", "SLA_RATIO", "SLA_BREACHED", "SLA_BUCKET", "STATUS",
                        "TRACKER", "PRIORITY", "STATUS_STAGE", "IS_CLOSED",
                        // V9: the close clock and At Risk are read-time too.
                        "REDMINE_IS_CLOSED", "STOP_AT", "CLOSE_BASIS", "SLA_AT_RISK");

        assertThat(viewColumns().keySet())
                .contains("AGING_NEW", "AGING_INCIDENT", "AGING_LAST_STATUS", "ELAPSED_DAYS",
                        "SLA_RATIO", "SLA_BREACHED", "SLA_BUCKET", "STATUS", "TRACKER",
                        "PRIORITY", "STATUS_STAGE", "IS_CLOSED", "MTTA_HOURS",
                        "REDMINE_IS_CLOSED", "STOP_AT", "CLOSE_BASIS", "SLA_AT_RISK");

        // STOP_AT is ONE type across its branches (the CAST in every branch), and the flags
        // are numbers, so they filter and aggregate without conversion.
        assertThat(viewColumns().get("STOP_AT")).isEqualTo("DATE");
        assertThat(viewColumns().get("CLOSE_BASIS")).isEqualTo("VARCHAR2");
        assertThat(viewColumns().get("SLA_AT_RISK")).isEqualTo("NUMBER");
    }

    @Test
    @DisplayName("the fact keeps both the raw enum id and the resolved label")
    void factKeepsBothTheRawEnumIdAndTheResolvedLabel() {
        // A REF_CUSTOM_FIELD_ENUM correction then re-labels history with an UPDATE driven by
        // the retained id, instead of a full re-ETL.
        assertThat(factColumns()).contains(
                "TYPE_OF_INCIDENT", "TYPE_OF_INCIDENT_ID",
                "CATEGORY_INCIDENT", "CATEGORY_INCIDENT_ID",
                "EXTERNAL_INTERNAL", "EXTERNAL_INTERNAL_ID",
                "IMPACT_SEVERITY", "IMPACT_SEVERITY_ID",
                "URGENCY_SEVERITY", "URGENCY_SEVERITY_ID",
                "INSTITUSI", "INSTITUSI_ID");
    }

    @Test
    @DisplayName("the ETL support tables exist and reset cleanly alongside the mart")
    void etlSupportTablesExist() {
        // AbstractOracleIT's @BeforeEach has already run resetAll(), which TRUNCATEs these
        // four now that they exist -- this test failing on ORA-00942 would mean the
        // "skip whatever is absent" logic broke when they appeared.
        assertThat(scalar("SELECT COUNT(*) FROM ETL_WATERMARK")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM ETL_RECONCILE_LOG")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT_REJECT")).isZero();

        // The quarantine table carries enough to DIAGNOSE a rejected value, not merely to
        // count one: which issue, which field, what the raw text was, and why it failed.
        assertThat(columnsOf("INCIDENT_FACT_REJECT").keySet())
                .contains("NO_INCIDENT", "CUSTOM_FIELD_ID", "LOGICAL_NAME", "RAW_VALUE",
                        "REASON", "DETECTED_AT");
    }

    // -------------------------------------------------------------------- LEFT-join safety

    @Test
    @DisplayName("an incident whose status/tracker/priority rows are missing still appears")
    void everyIncidentSurvivesAMissingDimensionRow() {
        // A known incident, fully resolvable, as the control.
        insertStatus(1, "New", 0);
        insertTracker(7, "Incident");
        insertPriority(4, "Low");
        insertFact(1001, /* tracker */ 7, /* status */ 1, /* priority */ 4L, /* sla */ 60,
                "SYSDATE - 1", /* closeDate */ null, /* hasCloseDateRow */ 0);

        // ...and one whose dimension rows are absent from the mirror: a Kafka gap, or rows
        // deleted in Redmine. An INNER join would drop this row from every aggregate.
        // 90001 is also absent from CFG_STATUS_STAGE_MAP, so its stage is unmapped.
        insertFact(1002, /* tracker */ 90002, /* status */ 90001, /* priority */ 90003L,
                /* sla */ 3, "SYSDATE - 1", null, 0);

        assertThat(scalar("SELECT COUNT(*) FROM V_INCIDENT")).isEqualTo(2);

        Map<String, Object> orphan = row(
                "SELECT STATUS, TRACKER, PRIORITY, STATUS_STAGE, EXCLUDE_FROM_BACKLOG, IS_CLOSED "
                        + "FROM V_INCIDENT WHERE NO_INCIDENT = 1002");
        assertThat(orphan.get("STATUS")).isEqualTo("Unknown (90001)");
        assertThat(orphan.get("TRACKER")).isEqualTo("Unknown (90002)");
        assertThat(orphan.get("PRIORITY")).isEqualTo("Unclassified");
        assertThat(orphan.get("STATUS_STAGE")).isEqualTo("Unmapped");
        // The NVL fallbacks on the numeric columns matter too: a NULL IS_CLOSED would drop
        // the row out of both sides of an open/closed split.
        assertThat(number(orphan.get("EXCLUDE_FROM_BACKLOG"))).isZero();
        assertThat(number(orphan.get("IS_CLOSED"))).isZero();

        Map<String, Object> known = row(
                "SELECT STATUS, TRACKER, PRIORITY FROM V_INCIDENT WHERE NO_INCIDENT = 1001");
        assertThat(known.get("STATUS")).isEqualTo("New");
        assertThat(known.get("TRACKER")).isEqualTo("Incident");
        assertThat(known.get("PRIORITY")).isEqualTo("Low");
    }

    // ------------------------------------------------------------------- the SLA semantics

    @Test
    @DisplayName("a NULL elapsed gets the Unknown bucket and SLA_BREACHED 0, never a NULL")
    void aNullElapsedGetsTheUnknownBucketNotANullBucket() {
        insertStatus(1, "New", 0);
        insertTracker(7, "Incident");
        insertPriority(4, "Low");

        // The 8% cohort: a custom_values row for close_date EXISTS but holds no readable
        // date (MySQL '' -> Oracle NULL). MySQL's timestampdiff(DAY, d, '') is NULL, so
        // AGING_NEW is NULL -- but the row must not vanish from the bucket chart.
        insertFact(2001, 7, 1, 4L, 60, "SYSDATE - 400", null, /* hasCloseDateRow */ 1);

        Map<String, Object> r = row("SELECT AGING_NEW, SLA_RATIO, SLA_BREACHED, SLA_BUCKET "
                + "FROM V_INCIDENT WHERE NO_INCIDENT = 2001");
        assertThat(r.get("AGING_NEW")).as("close_date row present but unreadable -> NULL")
                .isNull();
        assertThat(r.get("SLA_RATIO")).isNull();
        assertThat(number(r.get("SLA_BREACHED")))
                .as("never NULL: a NULL breach flag disappears from both sides of a split")
                .isZero();
        assertThat(r.get("SLA_BUCKET"))
                .as("its OWN band, not a NULL bucket that silently drops out of the chart")
                .isEqualTo("Unknown");

        // The distinction the HAS_CLOSE_DATE_ROW guard exists for: NO row at all means
        // "still open", which ages against SYSDATE rather than going NULL.
        insertFact(2002, 7, 1, 4L, 60, "SYSDATE - 400", null, /* hasCloseDateRow */ 0);
        Map<String, Object> open = row("SELECT AGING_NEW, SLA_BUCKET FROM V_INCIDENT "
                + "WHERE NO_INCIDENT = 2002");
        assertThat(number(open.get("AGING_NEW"))).isEqualTo(400);
        assertThat(open.get("SLA_BUCKET")).isEqualTo("Breached");
    }

    @Test
    @DisplayName("a closed-but-late incident is still a breach")
    void closingLateIsStillABreach() {
        insertStatus(5, "Closed", 1);
        insertTracker(7, "Incident");
        insertPriority(6, "Critical");

        // Created 60 days ago, closed 30 days ago, 3-day SLA. An earlier draft
        // short-circuited `IS_CLOSED = 1 THEN 0`, which made every closed incident
        // unbreachable and deleted the entire workaround-debt population from breach
        // reporting.
        insertFact(3001, 7, 5, 6L, 3, "SYSDATE - 60", "TRUNC(SYSDATE) - 30",
                /* hasCloseDateRow */ 1);

        Map<String, Object> r = row("SELECT IS_CLOSED, AGING_NEW, SLA_BREACHED, SLA_BUCKET, "
                + "SLA_RATIO, TTR_PERMANENT_DAYS FROM V_INCIDENT WHERE NO_INCIDENT = 3001");
        assertThat(number(r.get("IS_CLOSED"))).isEqualTo(1);
        assertThat(number(r.get("AGING_NEW")))
                .as("elapsed stops at the close date, it does not keep running")
                .isEqualTo(30);
        assertThat(number(r.get("SLA_BREACHED"))).isEqualTo(1);
        assertThat(r.get("SLA_BUCKET")).isEqualTo("Breached");
        assertThat(number(r.get("SLA_RATIO"))).isEqualTo(10);
        assertThat(number(r.get("TTR_PERMANENT_DAYS"))).isEqualTo(30);
    }

    @Test
    @DisplayName("SLA_BREACHED and SLA_BUCKET never disagree, because they share one elapsed")
    void slaBreachedAgreesWithSlaBucketForEveryRow() {
        insertStatus(1, "New", 0);
        insertStatus(5, "Closed", 1);
        insertTracker(7, "Incident");
        insertPriority(4, "Low");

        insertFact(4001, 7, 1, 4L, 10, "SYSDATE - 2", null, 0);                      // 0-50%
        insertFact(4002, 7, 1, 4L, 10, "SYSDATE - 6", null, 0);                      // 50-80%
        insertFact(4003, 7, 1, 4L, 10, "SYSDATE - 9", null, 0);                      // 80-100%
        insertFact(4004, 7, 1, 4L, 10, "SYSDATE - 40", null, 0);                     // Breached
        insertFact(4005, 7, 5, 4L, 10, "SYSDATE - 40", "TRUNC(SYSDATE) - 1", 1);     // Breached
        insertFact(4006, 7, 1, 4L, 10, "SYSDATE - 40", null, 1);                     // Unknown

        assertThat(scalar("SELECT COUNT(*) FROM V_INCIDENT WHERE "
                + "(SLA_BREACHED = 1 AND SLA_BUCKET <> 'Breached') OR "
                + "(SLA_BREACHED = 0 AND SLA_BUCKET = 'Breached')"))
                .as("one shared ELAPSED_DAYS is the only thing that keeps these in step")
                .isZero();

        Map<String, Long> buckets = new LinkedHashMap<>();
        forEachRow("SELECT SLA_BUCKET, COUNT(*) FROM V_INCIDENT GROUP BY SLA_BUCKET",
                rs -> buckets.put(rs.getString(1), rs.getLong(2)));
        assertThat(buckets).containsExactlyInAnyOrderEntriesOf(
                Map.of("0-50%", 1L, "50-80%", 1L, "80-100%", 1L, "Breached", 2L, "Unknown", 1L));

        // Every row lands in exactly one band: nothing vanishes from the chart.
        assertThat(buckets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(6);
    }

    // ---------------------------------------------------------------- report-format columns

    @Test
    @DisplayName("the view exposes the RPT_* report renderings and the SLA alias")
    void viewExposesTheReportFormatColumnsAndTheSlaAlias() {
        Map<String, String> columns = viewColumns();

        // SC003/SC004/SC006/SC007 are STRINGS in the delivered workbook, not timestamps.
        assertThat(columns).containsKeys("RPT_DATE_CREATE", "RPT_CREATE_DATE",
                "RPT_UPDATED_ON", "RPT_CLOSED_ON");
        assertThat(columns.get("RPT_DATE_CREATE")).isEqualTo("VARCHAR2");
        assertThat(columns.get("RPT_CREATE_DATE")).isEqualTo("VARCHAR2");
        assertThat(columns.get("RPT_UPDATED_ON")).isEqualTo("VARCHAR2");
        assertThat(columns.get("RPT_CLOSED_ON")).isEqualTo("VARCHAR2");

        // SC011: the workbook header is SLA. The parity reader normalises headers by
        // stripping underscores, so SLA_DAYS would become SLADAYS and never match.
        assertThat(columns.get("SLA")).isEqualTo("NUMBER");
        // Storage keeps its native name; the alias is additive, not a rename.
        assertThat(columns).containsKey("SLA_DAYS");

        insertStatus(1, "New", 0);
        insertTracker(7, "Incident");
        insertPriority(4, "Low");
        insertFact(5001, 7, 1, 4L, 60, "TIMESTAMP '2026-03-04 09:15:00'", null, 0);
        exec("UPDATE INCIDENT_FACT SET UPDATED_ON = TIMESTAMP '2026-03-05 10:00:00', "
                + "CLOSED_ON = TIMESTAMP '2026-03-06 11:00:00' WHERE NO_INCIDENT = 5001");

        Map<String, Object> r = row("SELECT RPT_DATE_CREATE, RPT_CREATE_DATE, RPT_UPDATED_ON, "
                + "RPT_CLOSED_ON, SLA FROM V_INCIDENT WHERE NO_INCIDENT = 5001");
        assertThat(r.get("RPT_DATE_CREATE")).isEqualTo("2026-03-04");
        assertThat(r.get("RPT_CREATE_DATE")).isEqualTo("20260304");
        assertThat(r.get("RPT_UPDATED_ON")).isEqualTo("20260305");
        assertThat(r.get("RPT_CLOSED_ON")).isEqualTo("20260306");
        assertThat(number(r.get("SLA"))).isEqualTo(60);

        // A NULL timestamp renders as NULL, not as the string 'null' or an epoch date.
        insertFact(5002, 7, 1, 4L, 60, "SYSDATE", null, 0);
        Map<String, Object> open = row("SELECT RPT_UPDATED_ON, RPT_CLOSED_ON FROM V_INCIDENT "
                + "WHERE NO_INCIDENT = 5002");
        assertThat(open.get("RPT_UPDATED_ON")).isNull();
        assertThat(open.get("RPT_CLOSED_ON")).isNull();
    }

    // ------------------------------------------------------------------------- fixture SQL

    /**
     * Inserts a fact row directly. There is no ETL yet (Task 11) and the VIEW is what is
     * under test here, so going through one would prove nothing about either.
     *
     * @param createExpr SQL expression for DATE_CREATE, e.g. {@code "SYSDATE - 30"}
     * @param closeExpr  SQL expression for CLOSE_DATE, or {@code null} for no date
     */
    private void insertFact(long id, long trackerId, long statusId, Long priorityId,
            int slaDays, String createExpr, String closeExpr, int hasCloseDateRow) {
        exec("INSERT INTO INCIDENT_FACT (NO_INCIDENT, TRACKER_ID, SUBJECT, DATE_CREATE, "
                + "CREATE_MONTH, STATUS_ID, PRIORITY_ID, SLA_DAYS, CLOSE_DATE, "
                + "HAS_CLOSE_DATE_ROW, LAST_DATE_STATUS, ETL_UPDATED_AT, SOURCE_UPDATED_ON) "
                + "VALUES (" + id + ", " + trackerId + ", 'incident " + id + "', "
                + createExpr + ", TO_CHAR(" + createExpr + ", 'YYYYMM'), "
                + statusId + ", " + (priorityId == null ? "NULL" : priorityId) + ", "
                + slaDays + ", " + (closeExpr == null ? "NULL" : closeExpr) + ", "
                + hasCloseDateRow + ", " + createExpr + ", SYSDATE, SYSDATE)");
    }

    private void insertStatus(long id, String name, int isClosed) {
        exec("INSERT INTO " + MirrorFixtureLoader.mirrorTable("issue_statuses")
                + " (ID, NAME, IS_CLOSED, POSITION) VALUES ("
                + id + ", '" + name + "', " + isClosed + ", 1)");
    }

    private void insertTracker(long id, String name) {
        exec("INSERT INTO " + MirrorFixtureLoader.mirrorTable("trackers")
                + " (ID, NAME, POSITION) VALUES (" + id + ", '" + name + "', 1)");
    }

    private void insertPriority(long id, String name) {
        // TYPE matters: the join is filtered to IssuePriority, because enumerations also
        // holds document categories and time-entry activities under the same ids.
        exec("INSERT INTO " + MirrorFixtureLoader.mirrorTable("enumerations")
                + " (ID, NAME, TYPE, POSITION, ACTIVE) VALUES ("
                + id + ", '" + name + "', 'IssuePriority', 1, 1)");
    }

    // ---------------------------------------------------------------------------- plumbing

    /** The column list Task 11 generates the MERGE from. */
    private List<String> factColumns() {
        return new ArrayList<>(columnsOf("INCIDENT_FACT").keySet());
    }

    /** {@code V_INCIDENT}'s columns -> Oracle datatype. Views live in USER_TAB_COLUMNS too. */
    private Map<String, String> viewColumns() {
        return columnsOf("V_INCIDENT");
    }

    private Map<String, String> columnsOf(String table) {
        Map<String, String> columns = new LinkedHashMap<>();
        forEachRow("SELECT COLUMN_NAME, DATA_TYPE FROM USER_TAB_COLUMNS "
                        + "WHERE TABLE_NAME = '" + table + "' ORDER BY COLUMN_ID",
                rs -> columns.put(rs.getString(1).toUpperCase(Locale.ROOT), rs.getString(2)));
        return columns;
    }

    private Map<String, Object> row(String sql) {
        Map<String, Object> values = new LinkedHashMap<>();
        forEachRow(sql, rs -> {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                values.put(rs.getMetaData().getColumnLabel(i).toUpperCase(Locale.ROOT),
                        rs.getObject(i));
            }
        });
        assertThat(values).as("query returned no row: " + sql).isNotEmpty();
        return values;
    }

    /** Normalises Oracle's NUMBER (BigDecimal) to a long for comparison. */
    private static long number(Object value) {
        assertThat(value).as("expected a number, got NULL").isNotNull();
        return ((Number) value).longValue();
    }

    private long scalar(String sql) {
        return number(row(sql).values().iterator().next());
    }

    private void exec(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to execute: " + sql, e);
        }
    }

    private void forEachRow(String sql, RowConsumer consumer) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                consumer.accept(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to query: " + sql, e);
        }
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }
}
