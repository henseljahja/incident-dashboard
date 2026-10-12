package id.co.yokke.incidentdashboard.config;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the seed in {@code V2__seed_config.sql}, which IS the parity contract: every id the
 * source report hardcodes lives in one of these tables, and a wrong or missing row is a wrong
 * column in the delivered workbook rather than a crash.
 *
 * <p>Several assertions deliberately JOIN the config tables against the Redmine mirror
 * fixtures instead of restating literals. Comparing a hand-written list to a hand-written
 * list proves only that the author was consistent with themselves; comparing it to the
 * extracted dump proves it matches Redmine.
 */
class ConfigTablesIT extends AbstractOracleIT {

    @BeforeEach
    void loadMirrorFixtures() {
        // The mirror is the oracle for the status names, the tracker names and the priority
        // ladder, so the config assertions below need it populated.
        loadFixtures();
    }

    @Test
    @DisplayName("seeds every custom field the report reads: 9 rows, 6 ENUM and 3 DATE")
    void seedsEveryCustomFieldTheReportUses() {
        assertThat(scalar("SELECT COUNT(*) FROM CFG_CUSTOM_FIELD_MAP")).isEqualTo(9);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_CUSTOM_FIELD_MAP WHERE FIELD_KIND = 'ENUM'"))
                .isEqualTo(6);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_CUSTOM_FIELD_MAP WHERE FIELD_KIND = 'DATE'"))
                .isEqualTo(3);

        // The id -> column mapping itself, not just the counts: swapping 93 (impact) with
        // 94 (urgency) keeps every count above green while inverting two report columns.
        Map<Long, String> expected = new LinkedHashMap<>();
        expected.put(41L, "TYPE_OF_INCIDENT");
        expected.put(77L, "CATEGORY_INCIDENT");
        expected.put(93L, "IMPACT_SEVERITY");
        expected.put(94L, "URGENCY_SEVERITY");
        expected.put(101L, "INSTITUSI");
        expected.put(349L, "EXTERNAL_INTERNAL");
        expected.put(105L, "CLOSE_DATE");
        expected.put(106L, "RESOLUTION_DATE");
        expected.put(110L, "MIGRATION_DATE");

        Map<Long, String> actual = new LinkedHashMap<>();
        forEachRow("SELECT CUSTOM_FIELD_ID, TARGET_COLUMN FROM CFG_CUSTOM_FIELD_MAP",
                rs -> actual.put(rs.getLong(1), rs.getString(2)));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);

        // Every field is active out of the box; a seed that shipped one deactivated would
        // silently blank that column.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_CUSTOM_FIELD_MAP WHERE IS_ACTIVE = 1"))
                .isEqualTo(9);
    }

    @Test
    @DisplayName("maps every in-scope Redmine status to exactly one lifecycle stage")
    void mapsEveryKnownStatusToALifecycleStage() {
        // 40, not the 41 the plan's prose claims -- its own stage table lists 40, and the
        // dump holds exactly 40 statuses with id <= 74. Ids 75-108 are HR-recruitment and
        // procurement statuses on out-of-scope trackers; they are deliberately unmapped and
        // surface as 'Unmapped'.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP")).isEqualTo(40);

        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP WHERE STAGE = 'Cancelled'"))
                .isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE STAGE = 'Cancelled' AND STATUS_ID IN (6, 55)")).isEqualTo(2);

        // Cancelled is the only stage excluded from backlog ageing, and both its rows are.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE EXCLUDE_FROM_BACKLOG = 1")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE EXCLUDE_FROM_BACKLOG = 1 AND STAGE <> 'Cancelled'")).isZero();

        // Zero rows outside the seven stages. The CHECK constraint should make this
        // impossible; asserting it too is what catches someone relaxing the constraint.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP WHERE STAGE NOT IN "
                + "('New', 'Triage', 'In Progress', 'Blocked', 'Resolved', 'Closed', 'Cancelled')"))
                .isZero();

        // Parity with the dump: every seeded STATUS_NAME must match issue_statuses.name for
        // the same id. This is the assertion that catches a transcription typo, which no
        // count-based check ever would.
        String statuses = MirrorFixtureLoader.mirrorTable("issue_statuses");
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP m JOIN " + statuses + " s "
                + "ON s.\"ID\" = m.STATUS_ID WHERE s.\"NAME\" <> m.STATUS_NAME")).isZero();
        // ...and no seeded id is absent from Redmine altogether.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP m WHERE NOT EXISTS "
                + "(SELECT 1 FROM " + statuses + " s WHERE s.\"ID\" = m.STATUS_ID)")).isZero();

        // Status 40 'Closed Wkaround' is is_closed = 1 in Redmine but stages as Resolved:
        // the incident still owes a permanent fix, and that workaround debt is the signal
        // this dashboard exists to surface. The disagreement with is_closed is the point.
        assertThat(string("SELECT STAGE FROM CFG_STATUS_STAGE_MAP WHERE STATUS_ID = 40"))
                .isEqualTo("Resolved");
        assertThat(scalar("SELECT \"IS_CLOSED\" FROM " + statuses + " WHERE \"ID\" = 40"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("journal prop map covers workaround, rootcause, developer and status change")
    void journalPropMapCoversWorkaroundRootcauseAndDeveloper() {
        assertThat(scalar("SELECT COUNT(*) FROM CFG_JOURNAL_PROP_MAP")).isEqualTo(4);

        // The three custom-field keys are numeric ids; the fourth is an attribute NAME. Both
        // live in journal_details.prop_key, which is why PROP_KEY is VARCHAR2 here.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_JOURNAL_PROP_MAP WHERE PROPERTY = 'cf' "
                + "AND PROP_KEY IN ('115', '116', '126')")).isEqualTo(3);

        assertThat(string("SELECT VALUE_KIND FROM CFG_JOURNAL_PROP_MAP WHERE PROP_KEY = '115'"))
                .isEqualTo("TEXT");
        assertThat(string("SELECT VALUE_KIND FROM CFG_JOURNAL_PROP_MAP WHERE PROP_KEY = '116'"))
                .isEqualTo("TEXT");
        // 126 is a USER, not TEXT: its value is a user id that Task 8 resolves via REF_USER.
        // Treating it as TEXT would print a bare number in the Developer_assigned column.
        assertThat(string("SELECT VALUE_KIND FROM CFG_JOURNAL_PROP_MAP WHERE PROP_KEY = '126'"))
                .isEqualTo("USER");

        // status_id is keyed by NAME and is an attribute, not a custom field. If PROPERTY were
        // dropped from the match, a custom field that happened to be keyed 'status_id' would
        // be read as the lifecycle history.
        assertThat(string("SELECT PROPERTY FROM CFG_JOURNAL_PROP_MAP WHERE PROP_KEY = 'status_id'"))
                .isEqualTo("attr");
        assertThat(string(
                "SELECT VALUE_KIND FROM CFG_JOURNAL_PROP_MAP WHERE PROP_KEY = 'status_id'"))
                .isEqualTo("STATUS");

        // Every prop key the map claims must actually appear in the journal fixture with the
        // property it was seeded under -- a 'cf' seeded as 'attr' would silently match nothing.
        String details = MirrorFixtureLoader.mirrorTable("journal_details");
        assertThat(scalar("SELECT COUNT(*) FROM CFG_JOURNAL_PROP_MAP m WHERE EXISTS "
                + "(SELECT 1 FROM " + details + " d WHERE d.\"PROP_KEY\" = m.PROP_KEY "
                + "AND d.\"PROPERTY\" = m.PROPERTY)")).isEqualTo(4);
    }

    @Test
    @DisplayName("tracker scope holds exactly the five ITIL trackers")
    void trackerScopeHoldsExactlyTheFiveItilTrackers() {
        assertThat(scalar("SELECT COUNT(*) FROM CFG_TRACKER_SCOPE")).isEqualTo(5);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_TRACKER_SCOPE "
                + "WHERE TRACKER_ID IN (7, 16, 32, 33, 41)")).isEqualTo(5);

        // Names checked against the trackers dump rather than against themselves.
        String trackers = MirrorFixtureLoader.mirrorTable("trackers");
        assertThat(scalar("SELECT COUNT(*) FROM CFG_TRACKER_SCOPE c JOIN " + trackers + " t "
                + "ON t.\"ID\" = c.TRACKER_ID WHERE t.\"NAME\" <> c.LOGICAL_NAME")).isZero();

        // No issue in the fixture sits on a tracker outside the scope table: if that ever
        // fails, the scope seed is short a tracker and those incidents would vanish silently.
        String issues = MirrorFixtureLoader.mirrorTable("issues");
        assertThat(scalar("SELECT COUNT(*) FROM " + issues + " i WHERE NOT EXISTS "
                + "(SELECT 1 FROM CFG_TRACKER_SCOPE c WHERE c.TRACKER_ID = i.\"TRACKER_ID\")"))
                .isZero();
    }

    @Test
    @DisplayName("SLA policy covers every IssuePriority in the mirror, with none falling through")
    void slaPolicyCoversEveryIssuePriorityInTheMirror() {
        String enumerations = MirrorFixtureLoader.mirrorTable("enumerations");

        // The real assertion: not "there are 4 rows" but "no priority Redmine knows about is
        // missing from the ladder". A missing one would silently take the query's ELSE branch
        // and be reported against a 3-day budget nobody chose for it.
        assertThat(scalar("SELECT COUNT(*) FROM " + enumerations + " e "
                + "WHERE e.\"TYPE\" = 'IssuePriority' AND NOT EXISTS "
                + "(SELECT 1 FROM CFG_SLA_POLICY p WHERE p.PRIORITY_ID = e.\"ID\")")).isZero();

        // And the names agree, so the ladder is readable as well as correct.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_SLA_POLICY p JOIN " + enumerations + " e "
                + "ON e.\"ID\" = p.PRIORITY_ID WHERE e.\"NAME\" <> p.PRIORITY_NAME")).isZero();

        // The ladder itself, keyed on ID. Note 19 = Medium sits between 3 = High and
        // 21 = Critical: the ids are neither contiguous nor severity-ordered, which is why
        // nothing may derive the SLA from an id ordering.
        assertThat(scalar("SELECT SLA_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 1"))
                .isEqualTo(60);
        assertThat(scalar("SELECT SLA_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 19"))
                .isEqualTo(14);
        assertThat(scalar("SELECT SLA_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 3"))
                .isEqualTo(7);
        assertThat(scalar("SELECT SLA_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 21"))
                .isEqualTo(3);

        // Every issue in the fixture resolves to a policy row too.
        String issues = MirrorFixtureLoader.mirrorTable("issues");
        assertThat(scalar("SELECT COUNT(*) FROM " + issues + " i WHERE NOT EXISTS "
                + "(SELECT 1 FROM CFG_SLA_POLICY p WHERE p.PRIORITY_ID = i.\"PRIORITY_ID\")"))
                .isZero();
    }

    @Test
    @DisplayName("any config write increments CFG_VERSION.GENERATION")
    void aConfigWriteIncrementsTheGeneration() {
        // GENERATION is the mart's cache-invalidation token. The bump is a trigger, not a
        // convention, precisely so that this holds for a write nobody remembered to pair with
        // a manual bump -- like this one.
        long before = scalar("SELECT GENERATION FROM CFG_VERSION");

        // A tracker id that cannot collide with Redmine's.
        execute("INSERT INTO CFG_TRACKER_SCOPE (TRACKER_ID, LOGICAL_NAME, IS_ACTIVE) "
                + "VALUES (999001, 'Generation Probe', 0)");
        try {
            long afterInsert = scalar("SELECT GENERATION FROM CFG_VERSION");
            assertThat(afterInsert).isGreaterThan(before);

            execute("UPDATE CFG_TRACKER_SCOPE SET LOGICAL_NAME = 'Generation Probe v2' "
                    + "WHERE TRACKER_ID = 999001");
            assertThat(scalar("SELECT GENERATION FROM CFG_VERSION")).isGreaterThan(afterInsert);
        } finally {
            // CFG_ tables are Flyway seed data and are NOT truncated between tests, so this
            // probe row has to clean up after itself or it leaks into every later assertion.
            // The DELETE bumps the generation as well, which is correct: removing a mapping
            // invalidates the mart exactly as adding one does.
            execute("DELETE FROM CFG_TRACKER_SCOPE WHERE TRACKER_ID = 999001");
        }

        assertThat(scalar("SELECT COUNT(*) FROM CFG_TRACKER_SCOPE")).isEqualTo(5);
        assertThat(scalar("SELECT GENERATION FROM CFG_VERSION")).isGreaterThan(before);

        // One row, ever.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_VERSION")).isEqualTo(1);
    }

    /**
     * TM011. V9 seeds the close override on exactly PTR (11) and PTR with permission (63), and
     * the per-priority at-risk cushions, keyed on PRIORITY_ID like the ladder itself.
     *
     * <p>The seed block is then RE-EXECUTED from the migration text itself: a V9 that fails half
     * way is re-run after {@code flyway repair}, so the block must write the same rows again and
     * move the generation by exactly one -- six trigger bumps would be an artefact of how the
     * seed is written, and zero would leave the mart stamped as current without the new column.
     */
    @Test
    @DisplayName("V9 seeds the close override and cushions, repair-safely, with one generation bump")
    void v9SeedsCloseOverrideAndCushions() throws IOException {
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP WHERE IS_CLOSED_OVERRIDE = 1"))
                .isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE IS_CLOSED_OVERRIDE = 1 AND STATUS_ID IN (11, 63)")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE IS_CLOSED_OVERRIDE IS NOT NULL")).as("NULL = use Redmine's flag").isEqualTo(2);
        // Closed-ness is not a stage change: PTR stays in the ops funnel's Blocked stage.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE STATUS_ID IN (11, 63) AND STAGE = 'Blocked'")).isEqualTo(2);

        Map<Long, Long> expectedCushions = Map.of(1L, 7L, 19L, 3L, 3L, 2L, 21L, 1L);
        assertThat(cushions()).containsExactlyInAnyOrderEntriesOf(expectedCushions);

        long before = scalar("SELECT GENERATION FROM CFG_VERSION");
        execute(v9SeedBlock());
        assertThat(scalar("SELECT GENERATION FROM CFG_VERSION"))
                .as("one config change, one re-ETL -- not one per trigger-fired row")
                .isEqualTo(before + 1);
        assertThat(cushions()).as("re-running the seed on repair writes the same rows")
                .containsExactlyInAnyOrderEntriesOf(expectedCushions);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_STATUS_STAGE_MAP "
                + "WHERE IS_CLOSED_OVERRIDE = 1 AND STATUS_ID IN (11, 63)")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM CFG_VERSION")).isEqualTo(1);
    }

    /**
     * TM010 / AC2.5. A cushion of SLA_DAYS or more flags a ticket as at risk on the day it is
     * raised; the CHECK makes that a refused write rather than a silently useless tile.
     */
    @Test
    @DisplayName("an at-risk cushion at or above the SLA days is refused by CFG_SLA_AT_RISK_CK")
    void atRiskCushionMustBeBelowSlaDays() {
        long generation = scalar("SELECT GENERATION FROM CFG_VERSION");

        // High: SLA 7. Equal and above are both refused; so is a negative cushion.
        for (String cushion : List.of("7", "8", "-1")) {
            assertThat(failure("UPDATE CFG_SLA_POLICY SET AT_RISK_REMAINING_DAYS = " + cushion
                    + " WHERE PRIORITY_ID = 3"))
                    .as("cushion " + cushion + " against a 7-day SLA")
                    .contains("CFG_SLA_AT_RISK_CK");
        }
        assertThat(scalar("SELECT AT_RISK_REMAINING_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 3"))
                .as("the refused write changed nothing")
                .isEqualTo(2);
        assertThat(scalar("SELECT GENERATION FROM CFG_VERSION"))
                .as("...and bumped nothing: the trigger's work rolls back with the statement")
                .isEqualTo(generation);

        // The boundary that IS allowed: one day below the SLA. Restored afterwards, because CFG_
        // rows are Flyway seed data shared by every later test in the JVM.
        execute("UPDATE CFG_SLA_POLICY SET AT_RISK_REMAINING_DAYS = 6 WHERE PRIORITY_ID = 3");
        try {
            assertThat(scalar(
                    "SELECT AT_RISK_REMAINING_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = 3"))
                    .isEqualTo(6);
        } finally {
            execute("UPDATE CFG_SLA_POLICY SET AT_RISK_REMAINING_DAYS = 2 WHERE PRIORITY_ID = 3");
        }

        // The override column's own CHECK: 0 or 1 (or NULL), nothing else.
        assertThat(failure("UPDATE CFG_STATUS_STAGE_MAP SET IS_CLOSED_OVERRIDE = 2 "
                + "WHERE STATUS_ID = 11")).contains("CFG_SSM_CLOSED_OVR_CK");
    }

    @Test
    @DisplayName("reference tables resolve the ids the fixtures use, and only those")
    void referenceTablesResolveTheIdsTheFixturesUse() {
        // REF_ rows arrive from the fixture CSVs, not from Flyway: custom_field_enumerations,
        // users and email_addresses were never delivered in the mirror.
        assertThat(scalar("SELECT COUNT(*) FROM REF_CUSTOM_FIELD_ENUM")).isPositive();
        assertThat(scalar("SELECT COUNT(*) FROM REF_USER")).isPositive();

        // Every ENUM-kind custom value in the fixture resolves EXCEPT id 999999, which is
        // seeded unresolvable on purpose so the 'Unknown (999999)' path stays reachable.
        String customValues = MirrorFixtureLoader.mirrorTable("custom_values");
        assertThat(scalar("SELECT COUNT(*) FROM " + customValues + " cv "
                + "JOIN CFG_CUSTOM_FIELD_MAP m ON m.CUSTOM_FIELD_ID = cv.\"CUSTOM_FIELD_ID\" "
                + "WHERE m.FIELD_KIND = 'ENUM' AND NOT EXISTS ("
                + "  SELECT 1 FROM REF_CUSTOM_FIELD_ENUM r "
                + "  WHERE r.CUSTOM_FIELD_ID = cv.\"CUSTOM_FIELD_ID\" "
                // DBMS_LOB.SUBSTR, not TO_CHAR: VALUE is a CLOB, and the ids it carries are
                // short numeric text. Comparing as text also avoids blowing up on the
                // deliberately malformed date values elsewhere in the fixture.
                + "    AND TO_CHAR(r.ENUM_VALUE_ID) = DBMS_LOB.SUBSTR(cv.\"VALUE\", 100, 1))"))
                .isEqualTo(1);
        assertThat(scalar(
                "SELECT COUNT(*) FROM REF_CUSTOM_FIELD_ENUM WHERE ENUM_VALUE_ID = 999999"))
                .isZero();

        // REF_USER must cover every author and assignee the issue fixture references, or
        // Task 8's label rules have nothing to resolve.
        String issues = MirrorFixtureLoader.mirrorTable("issues");
        assertThat(scalar("SELECT COUNT(*) FROM " + issues + " i WHERE i.\"AUTHOR_ID\" IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM REF_USER u WHERE u.USER_ID = i.\"AUTHOR_ID\")"))
                .isZero();
        assertThat(scalar("SELECT COUNT(*) FROM " + issues
                + " i WHERE i.\"ASSIGNED_TO_ID\" IS NOT NULL AND NOT EXISTS "
                + "(SELECT 1 FROM REF_USER u WHERE u.USER_ID = i.\"ASSIGNED_TO_ID\")")).isZero();

        // At least one user has no ADDRESS: that is the row the assignee label rule must fall
        // back to LASTNAME for, and without it that branch is never exercised.
        assertThat(scalar("SELECT COUNT(*) FROM REF_USER WHERE ADDRESS IS NULL")).isPositive();
        assertThat(scalar("SELECT COUNT(*) FROM REF_USER WHERE LASTNAME IS NULL")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM REF_USER WHERE FIRSTNAME IS NULL")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private interface RowHandler {
        void accept(ResultSet rs) throws SQLException;
    }

    private void forEachRow(String sql, RowHandler handler) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                handler.accept(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private long scalar(String sql) {
        // Connection AND Statement both in the try-with-resources: closing only the Statement
        // leaks a pooled Connection per call and eventually hangs the suite.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private String string(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("statement failed: " + sql, e);
        }
    }

    /** The Oracle error text of a statement that is expected to be refused. */
    private String failure(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected the statement to be refused: " + sql);
    }

    private Map<Long, Long> cushions() {
        Map<Long, Long> cushions = new LinkedHashMap<>();
        forEachRow("SELECT PRIORITY_ID, AT_RISK_REMAINING_DAYS FROM CFG_SLA_POLICY",
                rs -> cushions.put(rs.getLong(1), rs.getLong(2)));
        return cushions;
    }

    /**
     * The PL/SQL seed block of V9, read from the migration on the classpath between its
     * {@code V9-SEED-BEGIN} / {@code V9-SEED-END} markers, minus the SQL*Plus-style {@code /}
     * terminator Flyway consumes. Read rather than retyped, so the test exercises the migration.
     */
    private static String v9SeedBlock() throws IOException {
        String migration;
        try (InputStream in = ConfigTablesIT.class.getResourceAsStream(
                "/db/migration/V9__close_clock_and_at_risk.sql")) {
            assertThat(in).as("V9 must be on the classpath").isNotNull();
            migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int begin = migration.indexOf("-- V9-SEED-BEGIN");
        int end = migration.indexOf("-- V9-SEED-END");
        assertThat(begin).as("seed begin marker").isNotNegative();
        assertThat(end).as("seed end marker").isGreaterThan(begin);
        String block = migration.substring(migration.indexOf('\n', begin) + 1, end).strip();
        assertThat(block).endsWith("/");
        return block.substring(0, block.length() - 1).strip();
    }
}
