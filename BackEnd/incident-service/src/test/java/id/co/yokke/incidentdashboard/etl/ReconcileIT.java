package id.co.yokke.incidentdashboard.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.yokke.incidentdashboard.domain.config.SchedulingConfig;
import id.co.yokke.incidentdashboard.domain.config.security.JwtService;
import id.co.yokke.incidentdashboard.domain.usecase.etl.FreshnessUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.FreshnessUseCase.Freshness;
import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.ReconcileUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.ReconcileUseCase.ReconcileResult;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.MirrorFixtureLoader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards the mart's backstop (Task 12): reconcile, prune, freshness, and the scheduler gate.
 *
 * <p>Three things carried over from {@code MartRefreshIT} that this class also depends on:
 *
 * <ul>
 *   <li>{@code AbstractOracleIT}'s {@code @BeforeEach} TRUNCATEs {@code ETL_WATERMARK}, so
 *       every method starts from COLD START. {@code freshnessReportsStaleOnColdStartWith
 *       NoWatermarkRow} exercises that state directly.
 *   <li>the {@code CFG_*} tables are Flyway seed data and are NOT in {@code resetAll()}'s
 *       truncate list. Any test that writes one MUST restore it in a {@code finally}, or it
 *       poisons every later test in the JVM — including the 27 that came before this task.
 *   <li>the container is shared, so nothing here may assume it is the first class to run.
 * </ul>
 */
@AutoConfigureMockMvc
class ReconcileIT extends AbstractOracleIT {

    /** The fixture's whole in-scope population: 100 issues, all tracker 7, all July 2026. */
    private static final int FIXTURE_ISSUES = 100;

    private static final String FIXTURE_MONTH = "202607";

    /**
     * Pins the two freshness thresholds so the assertions below are about ONE condition each.
     *
     * <p>The mirror-lag threshold is pushed effectively to infinity because the fixtures are a
     * frozen snapshot — their newest {@code updated_on} is permanently months behind wall
     * clock, so the mirror-lag condition would be true in every test and
     * {@code freshnessGoesStaleWhenTheWatermarkStopsMoving} would pass without ever exercising
     * the watermark. The refresh interval is dropped to 1s so "four missed intervals" is a
     * threshold a test can cross deliberately rather than by sleeping.
     */
    @DynamicPropertySource
    static void freshnessThresholds(DynamicPropertyRegistry registry) {
        registry.add("incident.etl.refresh-interval-ms", () -> "1000");
        registry.add("incident.etl.mirror-lag-threshold-seconds", () -> "315360000");
    }

    @Autowired
    private MockMvc mockMvc;

    private JdbcTemplate jdbc;

    private MartRefreshUseCase refresher;

    private ReconcileUseCase reconciler;

    private FreshnessUseCase freshness;

    @BeforeEach
    void wire() {
        jdbc = new JdbcTemplate(dataSource);
        refresher = useCase(MartRefreshUseCase.class);
        reconciler = useCase(ReconcileUseCase.class);
        freshness = useCase(FreshnessUseCase.class);
    }

    // --------------------------------------------------------------- the scheduler gate

    @Test
    @DisplayName("no scheduler is registered under test — asserted, not assumed")
    void schedulingIsDisabledUnderTest() {
        // AbstractOracleIT sets incident.etl.scheduling-enabled=false, and SchedulingConfig is
        // @ConditionalOnProperty on it. A tick firing mid-test would race resetAll()'s TRUNCATE
        // and MERGE into a half-loaded mirror, producing intermittent failures that read as
        // real ETL bugs. This asserts the flag actually took effect rather than trusting it.
        assertThat(context.getBeanNamesForType(SchedulingConfig.class)).isEmpty();

        // ...and, one level deeper, that nothing else registered a scheduled task either.
        ScheduledAnnotationBeanPostProcessor processor = context
                .getBeanProvider(ScheduledAnnotationBeanPostProcessor.class)
                .getIfAvailable();
        Set<ScheduledTask> tasks = processor == null ? Set.of() : processor.getScheduledTasks();
        assertThat(tasks).isEmpty();
    }

    // ---------------------------------------------------------------------- reconcile

    @Test
    @Timeout(180)
    @DisplayName("a freshly loaded mart reconciles to zero drift, and the clean run is logged")
    void reportsZeroDriftOnAHealthyMart() {
        loadFixtures();
        refresher.refresh();

        ReconcileResult result = reconciler.reconcile();

        assertThat(result.clean()).isTrue();
        assertThat(result.driftCount()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(result.extra()).isZero();
        assertThat(result.staleMonths()).isZero();
        assertThat(result.monthsChecked()).isEqualTo(1);

        // A clean run is logged too: an empty ETL_RECONCILE_LOG must be distinguishable from a
        // healthy one, because a reconcile that stopped running is itself the failure.
        assertThat(scalar("SELECT COUNT(*) FROM ETL_RECONCILE_LOG "
                + "WHERE SCOPE_MONTH = '" + FIXTURE_MONTH + "' AND DRIFT_COUNT = 0"))
                .isEqualTo(1);
    }

    @Test
    @Timeout(180)
    @DisplayName("a fact row that went missing is detected and re-merged")
    void detectsAndRepairsDriftWhenAFactRowWentMissing() {
        loadFixtures();
        refresher.refresh();
        long victim = scalarLong("SELECT MIN(NO_INCIDENT) FROM INCIDENT_FACT");
        jdbc.update("DELETE FROM INCIDENT_FACT WHERE NO_INCIDENT = ?", victim);

        ReconcileResult result = reconciler.reconcile();

        assertThat(result.driftCount()).isEqualTo(1);
        assertThat(result.missing()).isEqualTo(1);
        // The repair is REPORTED, never silent: it is in the result, and in ETL_RECONCILE_LOG.
        assertThat(result.repaired()).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isEqualTo(FIXTURE_ISSUES);
        assertThat(string("SELECT ACTION_TAKEN FROM ETL_RECONCILE_LOG "
                + "WHERE SCOPE_MONTH = '" + FIXTURE_MONTH + "' AND DRIFT_COUNT > 0 "
                + "ORDER BY RUN_ID DESC FETCH FIRST 1 ROWS ONLY"))
                .contains("re-merged month");

        // And the repaired mart now reconciles clean.
        assertThat(reconciler.reconcile().clean()).isTrue();
    }

    @Test
    @Timeout(180)
    @DisplayName("an in-place custom_values edit is invisible to the watermark but not to the "
            + "reconcile")
    void detectsAnInPlaceCustomValueEditThatNeverTouchedTheIssue() {
        loadFixtures();
        refresher.refresh();

        // The OLDEST-cursor issue, deliberately: refresh() rewinds only 300s from the newest
        // source timestamp, so this row is far outside the re-read window. Picking an
        // arbitrary issue would let the overlap re-merge it and mask the blind spot.
        long issueId = scalarLong("SELECT i.ID FROM " + issues() + " i "
                + "WHERE EXISTS (SELECT 1 FROM " + customValues() + " cv "
                + "              WHERE cv.CUSTOMIZED_TYPE = 'Issue' AND cv.CUSTOMIZED_ID = i.ID) "
                + "ORDER BY NVL(i.UPDATED_ON, i.CREATED_ON), i.ID FETCH FIRST 1 ROWS ONLY");
        jdbc.update("UPDATE " + customValues() + " SET VALUE = ? "
                + "WHERE CUSTOMIZED_TYPE = 'Issue' AND CUSTOMIZED_ID = ? "
                + "AND ID = (SELECT MIN(ID) FROM " + customValues()
                + "          WHERE CUSTOMIZED_TYPE = 'Issue' AND CUSTOMIZED_ID = ?)",
                "reconcile-drift-probe", issueId, issueId);

        // THE BLIND SPOT: custom_values carries no timestamp, so issues.updated_on did not
        // move and the watermark ETL cannot see the edit at all.
        assertThat(refresher.refresh().changed()).isZero();

        // The reconcile does, because CV_DIGEST is one of its drift terms. Without that term
        // it would be a second copy of the blind spot above.
        ReconcileResult result = reconciler.reconcile();
        assertThat(result.driftCount()).isEqualTo(1);
        assertThat(result.staleMonths()).isEqualTo(1);
        // Counts agreed throughout — this drift was checksum-only.
        assertThat(result.missing()).isZero();
        assertThat(result.extra()).isZero();
    }

    // -------------------------------------------------------------------------- prune

    @Test
    @Timeout(180)
    @DisplayName("prune removes fact rows whose tracker left CFG_TRACKER_SCOPE")
    void pruneRemovesIncidentsThatLeftTrackerScope() {
        loadFixtures();
        refresher.refresh();
        assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isEqualTo(FIXTURE_ISSUES);

        // CFG_TRACKER_SCOPE is Flyway SEED DATA and is NOT truncated by resetAll(). Capture
        // exactly which rows were active so the finally restores exactly those -- a blanket
        // "SET IS_ACTIVE = 1" would activate trackers that were deliberately off and poison
        // every later test in this JVM.
        List<Long> active = jdbc.queryForList(
                "SELECT TRACKER_ID FROM CFG_TRACKER_SCOPE WHERE IS_ACTIVE = 1", Long.class);
        assertThat(active).isNotEmpty();
        try {
            jdbc.update("UPDATE CFG_TRACKER_SCOPE SET IS_ACTIVE = 0 WHERE IS_ACTIVE = 1");

            int pruned = reconciler.pruneOutOfScope();

            assertThat(pruned).isEqualTo(FIXTURE_ISSUES);
            assertThat(scalar("SELECT COUNT(*) FROM INCIDENT_FACT")).isZero();
            // The one sanctioned delete says what it deleted.
            assertThat(scalar("SELECT COUNT(*) FROM ETL_RECONCILE_LOG WHERE ACTION_TAKEN = "
                    + "'pruned " + FIXTURE_ISSUES + " out-of-scope rows'")).isEqualTo(1);
        } finally {
            for (Long trackerId : active) {
                jdbc.update("UPDATE CFG_TRACKER_SCOPE SET IS_ACTIVE = 1 WHERE TRACKER_ID = ?",
                        trackerId);
            }
        }
        // Proves the restore landed: the next test in this JVM sees the seeded scope again.
        assertThat(scalar("SELECT COUNT(*) FROM CFG_TRACKER_SCOPE WHERE IS_ACTIVE = 1"))
                .isEqualTo(active.size());
    }

    // ---------------------------------------------------------------------- freshness

    @Test
    @DisplayName("cold start: no watermark row means never run, not 1970")
    void freshnessReportsStaleOnColdStartWithNoWatermarkRow() {
        // resetAll() truncated ETL_WATERMARK and nothing has run, which is the real state of a
        // freshly migrated schema.
        assertThat(scalar("SELECT COUNT(*) FROM ETL_WATERMARK")).isZero();

        Freshness result = freshness.freshness();

        assertThat(result.stale()).isTrue();
        assertThat(result.staleReason()).isEqualTo("etl-never-run");
        // NULL, not epoch. An epoch-formatted date renders on the wallboard as "last updated
        // 1 Jan 1970" and reads as a data bug rather than as "the ETL has not run yet".
        assertThat(result.lastRunAt()).isNull();
        assertThat(result.lastSourceTs()).isNull();
        assertThat(result.ageSeconds()).isNull();
    }

    @Test
    @Timeout(180)
    @DisplayName("freshness goes stale once the watermark stops moving")
    void freshnessGoesStaleWhenTheWatermarkStopsMoving() {
        loadFixtures();
        refresher.refresh();

        // A run just happened: LAST_RUN_AT is now, so nothing is stale.
        Freshness afterRun = freshness.freshness();
        assertThat(afterRun.stale()).isFalse();
        assertThat(afterRun.staleReason()).isNull();
        assertThat(afterRun.lastRunAt()).isNotNull();
        assertThat(afterRun.ageSeconds()).isNotNull().isLessThan(4L);

        // Now the scheduler dies. The refresh interval is pinned to 1s above, so backdating
        // LAST_RUN_AT by ten minutes is 600 missed intervals -- comfortably past the 4x
        // threshold, with no sleep and therefore no flakiness.
        jdbc.update("UPDATE ETL_WATERMARK SET LAST_RUN_AT = LAST_RUN_AT - INTERVAL '10' MINUTE");

        Freshness stalled = freshness.freshness();
        assertThat(stalled.stale()).isTrue();
        assertThat(stalled.staleReason()).isEqualTo("etl-stalled");
        assertThat(stalled.ageSeconds()).isGreaterThan(500L);
        // The watermark itself did not move: staleness is about the RUN, not the data.
        assertThat(stalled.lastSourceTs()).isEqualTo(afterRun.lastSourceTs());
    }

    @Test
    @Timeout(180)
    @DisplayName("GET /api/freshness returns lastRunAt, lastSourceTs, ageSeconds and stale")
    void freshnessEndpointReturnsTheContractedShape() throws Exception {
        loadFixtures();
        refresher.refresh();

        // Task 14 put /api/freshness behind AuthFilter + PrivilegeInterceptor, so an
        // anonymous call is now a 401 by design. The subject of this test is the payload
        // SHAPE, not reachability without a credential, so it presents one: ops.manager
        // (V7__seed_test_users.sql) holds incident.ops:view, one of the three menus the
        // route accepts. AuthFlowIT owns the authorization assertions themselves.
        String token = useCase(JwtService.class).createToken("ops.manager");

        mockMvc.perform(get("/api/freshness")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // ISO-8601 strings, not epoch millis: the wallboard renders these directly.
                .andExpect(jsonPath("$.lastRunAt").isString())
                .andExpect(jsonPath("$.lastSourceTs").isString())
                // Computed server-side from SYSDATE. A browser clock skewed by ten minutes
                // must not be able to declare the mart stale or fresh.
                .andExpect(jsonPath("$.ageSeconds").isNumber())
                .andExpect(jsonPath("$.stale").isBoolean())
                .andExpect(jsonPath("$.stale").value(false));
    }

    // ------------------------------------------------------------------------ helpers

    private static String issues() {
        return MirrorFixtureLoader.mirrorTable("issues");
    }

    private static String customValues() {
        return MirrorFixtureLoader.mirrorTable("custom_values");
    }

    private int scalar(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private long scalarLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private String string(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }
}
