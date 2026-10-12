package id.co.yokke.incidentdashboard.domain.usecase.etl;

import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartOpsRepository;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartOpsRepository.WatermarkState;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * THE single source of "is the dashboard showing live data?" (Task 12).
 *
 * <h2>Why exactly one implementation</h2>
 *
 * <p>The ops heartbeat, the wallboard's stale banner and the exec view all read
 * {@code GET /api/freshness}. No view computes staleness for itself. Two independent
 * implementations is precisely how two screens end up disagreeing about whether the numbers on
 * them are live, and the screen that says "live" is always the one someone believes.
 *
 * <h2>Two conditions, not one</h2>
 *
 * <p>{@code stale} is true when EITHER holds, and the response names which:
 *
 * <ul>
 *   <li><b>Our scheduler stalled</b> — {@code LAST_RUN_AT} is older than four refresh
 *       intervals. Four rather than one so a single skipped tick is not an incident.
 *   <li><b>The mirror stalled</b> — the newest {@code issues.updated_on} in scope has fallen
 *       further than {@code mirror-lag-threshold-seconds} behind wall clock. This is the ONLY
 *       way to see the Kafka sync itself dying: our ETL keeps running happily over frozen
 *       data, {@code LAST_RUN_AT} stays healthy, and the dashboard presents hours-old numbers
 *       as live. That failure is the entire reason this indicator exists.
 * </ul>
 *
 * <h2>Cold start is not an error</h2>
 *
 * <p>An absent {@code ETL_WATERMARK} row means NEVER RUN. It is the real state of a freshly
 * migrated schema and of every integration test (the fixture loader truncates the table). So:
 * {@code lastRunAt} is {@code null} — NOT epoch, which renders on a wallboard as "last updated
 * 1 Jan 1970" and reads as a data bug rather than as "the ETL has not run yet" — and
 * {@code stale} is {@code true}, because nothing has been loaded.
 *
 * <p>{@code ageSeconds} is computed by Oracle from {@code SYSDATE} (never
 * {@code SYSTIMESTAMP}), not by the SPA: a browser clock skewed by ten minutes must not be
 * able to declare the mart stale or fresh.
 */
@Service
@Lazy
public class FreshnessUseCase {

    private static final Logger log = LoggerFactory.getLogger(FreshnessUseCase.class);

    /** How many missed refresh intervals count as a stall rather than a hiccup. */
    private static final int STALL_INTERVALS = 4;

    private final MartOpsRepository repository;
    private final long refreshIntervalMs;
    private final long mirrorLagThresholdSeconds;

    public FreshnessUseCase(
            MartOpsRepository repository,
            @Value("${incident.etl.refresh-interval-ms:120000}") long refreshIntervalMs,
            @Value("${incident.etl.mirror-lag-threshold-seconds:3600}")
                    long mirrorLagThresholdSeconds) {
        this.repository = repository;
        this.refreshIntervalMs = refreshIntervalMs;
        this.mirrorLagThresholdSeconds = mirrorLagThresholdSeconds;
    }

    /**
     * The {@code /api/freshness} payload.
     *
     * @param lastRunAt when the ETL last completed a run, or {@code null} on cold start
     * @param lastSourceTs the source timestamp the ETL has reached, or {@code null} on cold
     *     start
     * @param ageSeconds seconds since {@code lastRunAt}, server-computed; {@code null} on cold
     *     start, because "no age" and "age 0" are different facts
     * @param stale whether the UI must show its stale banner
     * @param staleReason which condition tripped, or {@code null} when fresh. Named so an
     *     operator can tell "our scheduler died" from "the Kafka sync died" without a log dive
     */
    public record Freshness(LocalDateTime lastRunAt, LocalDateTime lastSourceTs, Long ageSeconds,
            boolean stale, String staleReason) {
    }

    public Freshness freshness() {
        Optional<WatermarkState> state = repository.watermarkState();
        Long mirrorLag = repository.mirrorLagSeconds();

        if (state.isEmpty() || state.get().lastRunAt() == null) {
            // COLD START. Nulls, not epoch — see the class javadoc.
            log.debug("freshness: cold start, no ETL run recorded");
            return new Freshness(null, null, null, true, "etl-never-run");
        }

        WatermarkState watermark = state.get();
        Long ageSeconds = watermark.ageSeconds();
        long stallThreshold = (refreshIntervalMs / 1000L) * STALL_INTERVALS;

        String reason = null;
        if (ageSeconds != null && ageSeconds > stallThreshold) {
            reason = "etl-stalled";
        } else if (mirrorLag != null && mirrorLag > mirrorLagThresholdSeconds) {
            // Checked SECOND but reported just as loudly: this one is invisible to LAST_RUN_AT.
            reason = "mirror-lagging";
        }

        return new Freshness(watermark.lastRunAt(), watermark.lastSourceTs(), ageSeconds,
                reason != null, reason);
    }
}
