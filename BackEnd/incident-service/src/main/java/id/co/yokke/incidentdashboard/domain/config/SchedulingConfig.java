package id.co.yokke.incidentdashboard.domain.config;

import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase.RefreshResult;
import id.co.yokke.incidentdashboard.domain.usecase.etl.ReconcileUseCase;
import id.co.yokke.incidentdashboard.domain.usecase.etl.ReconcileUseCase.ReconcileResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The application's only scheduler (Task 12): the incremental ETL tick and the reconcile tick.
 *
 * <h2>Why it is gated on a property</h2>
 *
 * <p>{@code incident.etl.scheduling-enabled} defaults to {@code true} in production and is set
 * to {@code false} by {@code AbstractOracleIT}'s {@code @DynamicPropertySource}. Without that
 * gate the scheduler fires mid-test and races {@code MirrorFixtureLoader.resetAll()} — a
 * background MERGE landing between a TRUNCATE and a fixture load produces failures that look
 * exactly like real ETL bugs but move from test to test between runs. Tier 2 flakiness that
 * nobody can reproduce is far more expensive than one property.
 *
 * <p>{@code @ConditionalOnProperty} on the {@code @Configuration} class rather than on the
 * methods, so with the flag off there is no {@code @EnableScheduling}, no
 * {@code TaskScheduler}, and no registered task at all — a state a test can ASSERT on rather
 * than infer. {@code ReconcileIT.schedulingIsDisabledUnderTest} does exactly that.
 *
 * <h2>Why the first tick is delayed by a whole interval</h2>
 *
 * <p>The scaffold context ({@code IncidentDashboardApplicationTests}) starts with no
 * {@code DataSource} at all — {@code application.yml} excludes the autoconfiguration. Deferring
 * the first run by one full interval means a short-lived context is gone long before a tick
 * could fire and touch a bean that cannot be built there.
 *
 * <h2>Why both bodies swallow their exceptions</h2>
 *
 * <p>An exception escaping a {@code @Scheduled} method cancels that task's future for the
 * lifetime of the JVM. One bad reconcile would therefore silently stop ALL later reconciles —
 * turning a transient failure into a permanently blind mart, with a green process and nothing
 * in the logs after the first stack trace. Log it, swallow it, run again next tick.
 *
 * <p>Logging carries run counts, watermarks and durations only — never incident text. See
 * {@code .agents/patterns/logging.md}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "incident.etl.scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final MartRefreshUseCase refresher;
    private final ReconcileUseCase reconciler;

    /**
     * {@code @Lazy} ON THE PARAMETERS, not merely on the bean definitions.
     *
     * <p>{@code @Lazy} on a definition only defers singleton PRE-instantiation; the moment an
     * eager bean like this one declares the dependency, Spring builds the real object. Both
     * use cases descend to a {@code JdbcTemplate}, and the scaffold context
     * ({@code IncidentDashboardApplicationTests}) has no {@code DataSource} at all —
     * {@code application.yml} excludes the autoconfiguration — so eager injection fails
     * context startup with {@code NoSuchBeanDefinitionException: JdbcTemplate}.
     *
     * <p>{@code @Lazy} at the INJECTION POINT is what yields a proxy: nothing is resolved
     * until a tick actually fires, and a context with no database simply never fires one.
     */
    public SchedulingConfig(@Lazy MartRefreshUseCase refresher,
            @Lazy ReconcileUseCase reconciler) {
        this.refresher = refresher;
        this.reconciler = reconciler;
    }

    /** Incremental, watermark-driven refresh. The use case declines to overlap itself. */
    @Scheduled(fixedDelayString = "${incident.etl.refresh-interval-ms:120000}",
            initialDelayString = "${incident.etl.refresh-interval-ms:120000}")
    public void refreshTick() {
        try {
            RefreshResult result = refresher.refresh();
            log.info("scheduled refresh: scanned={} changed={} rejected={} watermark={}",
                    result.scanned(), result.changed(), result.rejected(), result.watermark());
        } catch (RuntimeException e) {
            // Swallowed ON PURPOSE -- see the class javadoc. Rethrowing kills the schedule.
            log.error("scheduled refresh failed; the next tick will retry", e);
        }
    }

    /**
     * The backstop: reconcile, then prune. In that order, because reconcile's re-merges can
     * only add or rewrite rows and the prune is what removes the ones that left scope.
     */
    @Scheduled(cron = "${incident.etl.reconcile-cron:0 15 * * * *}")
    public void reconcileTick() {
        try {
            ReconcileResult result = reconciler.reconcile();
            int pruned = reconciler.pruneOutOfScope();
            log.info("scheduled reconcile: months={} drift={} repaired={} pruned={}",
                    result.monthsChecked(), result.driftCount(), result.repaired(), pruned);
        } catch (RuntimeException e) {
            log.error("scheduled reconcile failed; the next tick will retry", e);
        }
    }
}
