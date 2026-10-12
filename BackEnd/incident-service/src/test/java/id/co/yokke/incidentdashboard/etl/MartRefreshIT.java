package id.co.yokke.incidentdashboard.etl;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase.RefreshResult;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Guards the mart's write path (Task 11): delta selection, the keyset cursor, the three-term
 * MERGE content predicate and date quarantine.
 *
 * <p>Everything here goes through the real {@code MartRefreshUseCase} bean against the real
 * fixtures. The two things worth knowing before reading:
 *
 * <ul>
 *   <li>{@code AbstractOracleIT}'s {@code @BeforeEach} TRUNCATEs {@code ETL_WATERMARK}, so
 *       every method below starts from COLD START — the seed row {@code V3__mart.sql} inserts
 *       is not there. That is deliberate: "no row" must mean "never ran".
 *   <li>the {@code CFG_*} tables are Flyway seed data and are NOT truncated, so any test that
 *       writes one must delete it again in a {@code finally} or it poisons every later test in
 *       the JVM.
 * </ul>
 */
class MartRefreshIT extends AbstractOracleIT {

    /** The fixture's whole in-scope population: 100 issues, all tracker 7, all July 2026. */
    private static final int FIXTURE_ISSUES = 100;

    private static final String FIXTURE_MONTH = "202607";

    /** 22885 carries {@code custom_values} 105 = 'not-a-date' — see fixtures/README.md. */
    private static final long UNPARSEABLE_DATE_ISSUE = 22885L;

    private MartRefreshUseCase refresher;

    @BeforeEach
    void loadMirrorFixtures() {
        loadFixtures();
        refresher = useCase(MartRefreshUseCase.class);
    }

    // ------------------------------------------------------------------------- cold start

    @Test
    @DisplayName("a cold-start run materialises every in-scope issue exactly once")
    void mergesNewIssuesIntoTheFact() {
        RefreshResult result = refresher.refresh();

        assertThat(result.scanned()).isEqualTo(FIXTURE_ISSUES);
        assertThat(result.changed()).isEqualTo(FIXTURE_ISSUES);
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isEqualTo(FIXTURE_ISSUES);

        // The scope came from CFG_TRACKER_SCOPE, not from a literal IN (7,16,32,33,41).
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT f WHERE f.TRACKER_ID NOT IN "
                + "(SELECT TRACKER_ID FROM CFG_TRACKER_SCOPE WHERE IS_ACTIVE = 1)")).isZero();

        // Labels were resolved through the Task 8 resolvers, not left NULL.
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT WHERE IMPACT_SEVERITY IS NULL"))
                .isZero();
        // ...including the unresolvable id, which stays VISIBLE rather than vanishing.
        assertThat(string("SELECT IMPACT_SEVERITY FROM INCIDENT_FACT WHERE NO_INCIDENT = "
                + UNPARSEABLE_DATE_ISSUE)).isEqualTo("Unknown (999999)");

        // Nothing clock-derived was materialised; the fact only carries write-time values.
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT WHERE SLA_DAYS IS NULL")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM V_INCIDENT")).isEqualTo(FIXTURE_ISSUES);
    }

    // --------------------------------------------------------------- the content predicate

    @Test
    @DisplayName("a second run over an unchanged source writes nothing")
    void anUnchangedSourceRowIsNotRewritten() {
        refresher.refresh();

        RefreshResult second = refresher.refresh();
        // The 300s overlap guarantees the delta is NOT empty on a re-run, which is exactly why
        // changed() cannot be delta.size(): it counts what the MERGE actually wrote.
        assertThat(second.scanned()).isPositive();
        assertThat(second.changed())
                .as("all three DECODE terms match, so WHEN MATCHED updates no row")
                .isZero();

        // And the same over the FULL population, not merely the overlap window.
        assertThat(refresher.refreshMonth(FIXTURE_MONTH).changed()).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isEqualTo(FIXTURE_ISSUES);
    }

    @Test
    @DisplayName("a custom-value edit re-merges the row even though updated_on did not move")
    void aCustomValueEditIsPickedUpViaCvDigest() {
        // The issue at the watermark, so the edit is inside the overlap window the incremental
        // run re-reads. custom_values has no timestamp of its own and is UPDATEd in place, so
        // CV_DIGEST is the ONLY thing that can notice this.
        long issueId = scalar("SELECT ID FROM " + mirror("issues")
                + " ORDER BY UPDATED_ON DESC, ID DESC FETCH FIRST 1 ROW ONLY");
        // Which fixture issue that is depends on the extract, so start it from a known state
        // rather than assuming it has no close_date row.
        exec("DELETE FROM " + mirror("custom_values") + " WHERE CUSTOMIZED_ID = " + issueId
                + " AND CUSTOM_FIELD_ID = 105");

        refresher.refresh();

        long updatedOnBefore = scalar("SELECT TO_NUMBER(TO_CHAR(UPDATED_ON, 'YYYYMMDDHH24MISS')) "
                + "FROM " + mirror("issues") + " WHERE ID = " + issueId);
        assertThat(scalar("SELECT HAS_CLOSE_DATE_ROW FROM INCIDENT_FACT WHERE NO_INCIDENT = "
                + issueId)).isZero();

        exec("INSERT INTO " + mirror("custom_values")
                + " (ID, CUSTOMIZED_TYPE, CUSTOMIZED_ID, CUSTOM_FIELD_ID, VALUE) "
                + "VALUES (990001, 'Issue', " + issueId + ", 105, TO_CLOB('2026-07-15'))");

        RefreshResult second = refresher.refresh();

        assertThat(scalar("SELECT TO_NUMBER(TO_CHAR(UPDATED_ON, 'YYYYMMDDHH24MISS')) FROM "
                + mirror("issues") + " WHERE ID = " + issueId))
                .as("the edit must NOT have moved issues.updated_on")
                .isEqualTo(updatedOnBefore);
        assertThat(second.changed()).isEqualTo(1);
        assertThat(scalar("SELECT HAS_CLOSE_DATE_ROW FROM INCIDENT_FACT WHERE NO_INCIDENT = "
                + issueId)).isEqualTo(1);
        assertThat(string("SELECT TO_CHAR(CLOSE_DATE, 'YYYY-MM-DD') FROM INCIDENT_FACT "
                + "WHERE NO_INCIDENT = " + issueId)).isEqualTo("2026-07-15");
    }

    @Test
    @DisplayName("a CFG_* edit re-derives already-merged rows via CFG_GENERATION")
    void aConfigChangeReDerivesAffectedRows() {
        refresher.refresh();
        assertThat(refresher.refreshMonth(FIXTURE_MONTH).changed()).isZero();

        long generationBefore = scalar("SELECT GENERATION FROM CFG_VERSION");
        try {
            // Any CFG_* write bumps the generation — the trigger makes it a property of the
            // schema rather than a convention somebody has to remember. IS_ACTIVE = 0 so the
            // probe row changes the GENERATION without widening the report's scope.
            exec("INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) "
                    + "VALUES (999999, 'T011 probe', 0)");
            assertThat(scalar("SELECT GENERATION FROM CFG_VERSION"))
                    .isGreaterThan(generationBefore);

            // Neither issues.updated_on nor any custom value moved, so ONLY the third DECODE
            // term can catch this. Without it the mart stays half-mapped, invisibly.
            assertThat(refresher.refreshMonth(FIXTURE_MONTH).changed())
                    .isEqualTo(FIXTURE_ISSUES);
            assertThat(scalar("SELECT COUNT(DISTINCT CFG_GENERATION) FROM INCIDENT_FACT"))
                    .isEqualTo(1);
        } finally {
            // MUST run: CFG_* tables are Flyway seed data and are NOT in resetAll()'s truncate
            // list, so a leaked probe row poisons every later test in this JVM.
            exec("DELETE FROM CFG_TRACKER_SCOPE WHERE TRACKER_ID = 999999");
        }
        assertThat(scalar("SELECT COUNT(*) FROM CFG_TRACKER_SCOPE")).isEqualTo(5);
    }

    // ------------------------------------------------------------------------ the cursor

    @Test
    @Timeout(180)
    @DisplayName("the keyset cursor terminates when rows share one UPDATED_ON second")
    void theKeysetCursorTerminatesAtTimestampPrecision() {
        // TIMESTAMP(0) is second precision, so this is the normal case, not a contrived one.
        // With a chunk smaller than the group, a timestamp-only cursor either loops forever
        // (re-reading the group) or skips the rest of it.
        exec("UPDATE " + mirror("issues") + " SET UPDATED_ON = TIMESTAMP '2026-08-20 12:00:00' "
                + "WHERE ID IN (SELECT ID FROM " + mirror("issues")
                + " ORDER BY ID FETCH FIRST 5 ROWS ONLY)");

        RefreshResult result = refresher.refresh(2);

        assertThat(result.scanned())
                .as("every row is scanned exactly once: no row is skipped and none repeats")
                .isEqualTo(FIXTURE_ISSUES);
        assertThat(result.changed()).isEqualTo(FIXTURE_ISSUES);
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isEqualTo(FIXTURE_ISSUES);
        assertThat(scalar("SELECT COUNT(*) FROM " + mirror("issues") + " i "
                + "WHERE NOT EXISTS (SELECT 1 FROM INCIDENT_FACT f WHERE f.NO_INCIDENT = i.ID)"))
                .isZero();
    }

    // ----------------------------------------------------------------------- quarantine

    @Test
    @DisplayName("an unparseable date is quarantined and the incident still lands in the fact")
    void anUnparseableDateIsQuarantinedNotNulled() {
        RefreshResult result = refresher.refresh();

        assertThat(result.rejected()).isEqualTo(1);
        Map<String, Object> reject = row("SELECT CUSTOM_FIELD_ID, LOGICAL_NAME, RAW_VALUE, "
                + "REASON, DETECTED_AT FROM INCIDENT_FACT_REJECT WHERE NO_INCIDENT = "
                + UNPARSEABLE_DATE_ISSUE);
        assertThat(number(reject.get("CUSTOM_FIELD_ID"))).isEqualTo(105);
        assertThat(reject.get("LOGICAL_NAME")).isEqualTo("close_date");
        assertThat(reject.get("RAW_VALUE")).isEqualTo("not-a-date");
        assertThat(reject.get("REASON")).asString().contains("not-a-date");
        assertThat(reject.get("DETECTED_AT")).isNotNull();

        // A NULL meaning "no date" and a NULL meaning "we could not read this" are different
        // facts. The incident is NOT dropped, the date IS null, and the row is badged.
        Map<String, Object> fact = row("SELECT CLOSE_DATE, HAS_CLOSE_DATE_ROW, HAS_DATE_REJECT "
                + "FROM INCIDENT_FACT WHERE NO_INCIDENT = " + UNPARSEABLE_DATE_ISSUE);
        assertThat(fact.get("CLOSE_DATE")).isNull();
        assertThat(number(fact.get("HAS_CLOSE_DATE_ROW")))
                .as("row presence, not the value: this is what SOLUTION and AGING_NEW turn on")
                .isEqualTo(1);
        assertThat(number(fact.get("HAS_DATE_REJECT"))).isEqualTo(1);

        // A corrected value CLEARS the quarantine rather than accumulating a duplicate.
        exec("UPDATE " + mirror("custom_values") + " SET VALUE = TO_CLOB('2026-07-20') "
                + "WHERE CUSTOMIZED_ID = " + UNPARSEABLE_DATE_ISSUE + " AND CUSTOM_FIELD_ID = 105");
        refresher.refreshMonth(FIXTURE_MONTH);
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT_REJECT WHERE NO_INCIDENT = "
                + UNPARSEABLE_DATE_ISSUE)).isZero();
        assertThat(scalar("SELECT HAS_DATE_REJECT FROM INCIDENT_FACT WHERE NO_INCIDENT = "
                + UNPARSEABLE_DATE_ISSUE)).isZero();
    }

    // ------------------------------------------------------------------- journal semantics

    @Test
    @DisplayName("the LATEST journal entry wins for every journal-derived column")
    void lastValueWinsForJournalDerivedFields() {
        // prop_key 115 is 'workaround' per CFG_JOURNAL_PROP_MAP; the ids here are higher than
        // any fixture journal so these two are unambiguously the newest.
        insertJournal(999001, 22868, "2026-07-10 09:00:00");
        insertJournal(999002, 22868, "2026-07-11 09:00:00");
        insertJournalDetail(9990001, 999001, "cf", "115", "FIRST WORKAROUND");
        insertJournalDetail(9990002, 999002, "cf", "115", "SECOND WORKAROUND");

        refresher.refresh();

        assertThat(string("SELECT DBMS_LOB.SUBSTR(WORKAROUND, 200) FROM INCIDENT_FACT "
                + "WHERE NO_INCIDENT = 22868"))
                .as("last value wins -- the source report's semantics, preserved exactly")
                .isEqualTo("SECOND WORKAROUND");

        // And the STATUS prop, whose "latest" is MAX(created_on) rather than MAX(journal_id),
        // stays consistent: no incident can change status before it first changed status.
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT "
                + "WHERE FIRST_STATUS_CHANGE_AT > LAST_DATE_STATUS")).isZero();
    }

    // ------------------------------------------------------------------------- fixture SQL

    private void insertJournal(long id, long issueId, String createdOn) {
        exec("INSERT INTO " + mirror("journals")
                + " (ID, JOURNALIZED_ID, JOURNALIZED_TYPE, USER_ID, CREATED_ON) VALUES ("
                + id + ", " + issueId + ", 'Issue', 5, TIMESTAMP '" + createdOn + "')");
    }

    private void insertJournalDetail(long id, long journalId, String property, String propKey,
            String value) {
        exec("INSERT INTO " + mirror("journal_details")
                + " (ID, JOURNAL_ID, PROPERTY, PROP_KEY, VALUE) VALUES ("
                + id + ", " + journalId + ", '" + property + "', '" + propKey + "', TO_CLOB('"
                + value + "'))");
    }

    private static String mirror(String logical) {
        return MirrorFixtureLoader.mirrorTable(logical);
    }

    // ---------------------------------------------------------------------------- plumbing

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

    private static long number(Object value) {
        assertThat(value).as("expected a number, got NULL").isNotNull();
        return ((Number) value).longValue();
    }

    private long scalar(String sql) {
        return number(row(sql).values().iterator().next());
    }

    private String string(String sql) {
        Object value = row(sql).values().iterator().next();
        return value == null ? null : value.toString();
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
