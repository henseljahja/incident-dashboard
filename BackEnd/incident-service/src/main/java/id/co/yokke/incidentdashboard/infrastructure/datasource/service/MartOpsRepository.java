package id.co.yokke.incidentdashboard.infrastructure.datasource.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The mart's operational read/write surface: reconcile, prune and freshness (Task 12).
 *
 * <h2>Why this shares {@link MartRefreshRepository}'s SQL file rather than owning its own</h2>
 *
 * <p>Every statement here is read out of {@code incident-delta.sql} through
 * {@link MartRefreshRepository#section(String)}, which means they arrive with the SAME
 * {@code ${scope}} predicate the ETL imports through — the tracker filter AND the rolling
 * report window, expanded from one definition.
 *
 * <p>This is the single most load-bearing decision in Task 12. {@code INCIDENT_FACT} holds
 * only the five in-scope trackers inside that window; the mirror's {@code ISSUES} holds every
 * Redmine tracker and all of history. A reconcile that spells its own mirror-side filter — or
 * omits one — compares an unscoped population against a scoped table, reports drift on every
 * month forever, fills {@code ETL_RECONCILE_LOG} with false positives, and destroys the only
 * backstop this system has against a Kafka gap.
 *
 * <p>{@code @Lazy} for the same reason the refresh repository is: the scaffold context excludes
 * {@code DataSourceAutoConfiguration}, so there is no {@code JdbcTemplate} to inject until an
 * integration profile supplies one.
 */
@Repository
@Lazy
public class MartOpsRepository {

    private final JdbcTemplate jdbc;
    private final MartRefreshRepository refreshRepository;

    public MartOpsRepository(JdbcTemplate jdbc, MartRefreshRepository refreshRepository) {
        this.jdbc = jdbc;
        this.refreshRepository = refreshRepository;
    }

    // ----------------------------------------------------------------------- reconcile

    /**
     * One month's population summary from either side of the comparison.
     *
     * @param rowChecksum {@code SUM(ORA_HASH(...))} over {@code (id, updated_on, status_id)}
     * @param cvChecksum {@code SUM} of the canonical {@code CV_DIGEST} — the term that catches
     *     an in-place {@code custom_values} edit, which touches no {@code issues} column and is
     *     therefore invisible to {@code rowChecksum} and to the watermark alike
     * @param minGeneration lowest {@code CFG_GENERATION} stamped on the month's fact rows;
     *     {@code null} on the mirror side, which has no notion of our config
     */
    public record MonthSummary(String createMonth, int count, java.math.BigDecimal rowChecksum,
            java.math.BigDecimal cvChecksum, Integer minGeneration) {
    }

    /** Mirror-side counts and checksums per {@code CREATE_MONTH}, inside the ETL's scope. */
    public List<MonthSummary> mirrorMonths() {
        return jdbc.query(refreshRepository.section("reconcile-mirror"), (rs, rowNum) ->
                new MonthSummary(rs.getString("CREATE_MONTH"), rs.getInt("MIRROR_COUNT"),
                        rs.getBigDecimal("ROW_CHECKSUM"), rs.getBigDecimal("CV_CHECKSUM"),
                        null));
    }

    /** Fact-side counts and checksums per {@code CREATE_MONTH}. */
    public List<MonthSummary> factMonths() {
        return jdbc.query(refreshRepository.section("reconcile-fact"), (rs, rowNum) ->
                new MonthSummary(rs.getString("CREATE_MONTH"), rs.getInt("FACT_COUNT"),
                        rs.getBigDecimal("ROW_CHECKSUM"), rs.getBigDecimal("CV_CHECKSUM"),
                        rs.getInt("MIN_GENERATION")));
    }

    /** The current config generation — the reconcile's third drift term. */
    public int cfgGeneration() {
        return refreshRepository.cfgGeneration();
    }

    /**
     * Deletes fact rows the report no longer covers, and returns how many.
     *
     * <p>See the {@code prune} section's header for why this DELETE is sanctioned where the
     * project otherwise forbids row removal, and why {@code refreshMonth} structurally cannot
     * do it.
     */
    public int prune() {
        return jdbc.update(refreshRepository.section("prune"));
    }

    /** Appends one row to {@code ETL_RECONCILE_LOG}. Clean runs are logged too. */
    public void logReconcile(String scopeMonth, int mirrorCount, int factCount, int driftCount,
            String actionTaken) {
        jdbc.update("INSERT INTO ETL_RECONCILE_LOG "
                + "(SCOPE_MONTH, MIRROR_COUNT, FACT_COUNT, DRIFT_COUNT, ACTION_TAKEN) "
                + "VALUES (?, ?, ?, ?, ?)",
                scopeMonth, mirrorCount, factCount, driftCount, truncate(actionTaken));
    }

    /** {@code ACTION_TAKEN} is {@code VARCHAR2(200 CHAR)}; a long summary must not throw. */
    private static String truncate(String actionTaken) {
        if (actionTaken == null || actionTaken.length() <= 200) {
            return actionTaken;
        }
        return actionTaken.substring(0, 197) + "...";
    }

    // ----------------------------------------------------------------------- freshness

    /**
     * The watermark row as the freshness endpoint sees it.
     *
     * @param ageSeconds seconds between {@code LAST_RUN_AT} and {@code SYSDATE}, computed by
     *     Oracle so every consumer agrees on one clock
     */
    public record WatermarkState(LocalDateTime lastRunAt, LocalDateTime lastSourceTs,
            Long ageSeconds) {
    }

    /**
     * The watermark, or {@link Optional#empty()} when the row is absent.
     *
     * <p>Absent means COLD START — never run — not an error. The row genuinely is absent on a
     * freshly migrated schema and in every integration test, because
     * {@code MirrorFixtureLoader.resetAll()} truncates {@code ETL_WATERMARK}. A
     * {@code queryForObject} here would throw {@code EmptyResultDataAccessException} and turn
     * the wallboard's heartbeat into a 500 on day one.
     */
    public Optional<WatermarkState> watermarkState() {
        List<WatermarkState> rows = jdbc.query(
                "SELECT LAST_RUN_AT, LAST_SOURCE_TS, "
                        + "(SYSDATE - CAST(LAST_RUN_AT AS DATE)) * 86400 AS AGE_SECONDS "
                        + "FROM ETL_WATERMARK WHERE WATERMARK_KEY = ?",
                (rs, rowNum) -> {
                    java.math.BigDecimal age = rs.getBigDecimal("AGE_SECONDS");
                    return new WatermarkState(
                            localDateTime(rs.getTimestamp("LAST_RUN_AT")),
                            localDateTime(rs.getTimestamp("LAST_SOURCE_TS")),
                            age == null ? null : age.longValue());
                },
                MartRefreshRepository.WATERMARK_KEY);
        return rows.stream().findFirst();
    }

    /**
     * How far the MIRROR itself has fallen behind wall clock, in seconds, or {@code null} when
     * the mirror holds no in-scope issue at all.
     *
     * <p>This is the half of staleness that watches the Kafka sync rather than our own
     * scheduler: a dead sync leaves the ETL running happily over frozen data with a healthy
     * {@code LAST_RUN_AT}.
     */
    public Long mirrorLagSeconds() {
        List<Long> rows = jdbc.query(refreshRepository.section("mirror-max-updated"),
                (rs, rowNum) -> {
                    java.math.BigDecimal lag = rs.getBigDecimal("LAG_SECONDS");
                    return lag == null ? null : lag.longValue();
                });
        List<Long> present = new ArrayList<>(rows);
        present.removeIf(java.util.Objects::isNull);
        return present.isEmpty() ? null : present.get(0);
    }

    private static LocalDateTime localDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
