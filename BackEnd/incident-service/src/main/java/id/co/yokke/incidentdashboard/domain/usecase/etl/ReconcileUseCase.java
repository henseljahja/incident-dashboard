package id.co.yokke.incidentdashboard.domain.usecase.etl;

import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase.RefreshResult;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartOpsRepository;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartOpsRepository.MonthSummary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * The mart's backstop (Task 12): compare mirror against fact, repair what drifted, prune what
 * left scope — and log all of it.
 *
 * <h2>Why a reconcile exists at all when the watermark ETL is correct</h2>
 *
 * <p>The watermark path is driven entirely by {@code issues.updated_on}. Three things move the
 * truth without moving that column:
 *
 * <ul>
 *   <li>a {@code custom_values} row edited IN PLACE — that table carries no timestamp at all,
 *       so the ETL is structurally blind to it;
 *   <li>a Kafka event dropped between Redmine and the mirror — the row never appears, and
 *       nothing ever asks for it again;
 *   <li>a {@code CFG_*} edit, which changes how an already-merged row should be derived while
 *       leaving the source untouched.
 * </ul>
 *
 * <p>Each is caught by its own drift term below. A reconcile that checksummed {@code issues}
 * alone would be a second copy of the watermark's blind spot wearing a different name.
 *
 * <h2>It repairs, and it says so</h2>
 *
 * <p>{@link #reconcile()} does NOT only report. Every drifted month is re-merged wholesale via
 * {@link MartRefreshUseCase#refreshMonth(String)} and the repair is written to
 * {@code ETL_RECONCILE_LOG} with the row count it changed. Silent self-healing would hide a
 * permanently broken ETL behind a permanently green dashboard, so the repair is never
 * invisible: it is in the return value, in the log table, and in the application log.
 *
 * <p>Clean months are logged too. A reconcile that stops running is itself the failure this
 * exists to detect, and an empty {@code ETL_RECONCILE_LOG} must be distinguishable from a
 * healthy one.
 *
 * <h2>Logging</h2>
 *
 * <p>Run ids, months, counts and durations only. Never {@code SUBJECT}, {@code INSTITUSI},
 * {@code ASSIGNED_TO}, {@code WORKAROUND} or {@code ROOTCAUSE} — this data is
 * production-derived. See {@code .agents/patterns/logging.md}.
 */
@Service
@Lazy
public class ReconcileUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcileUseCase.class);

    private final MartOpsRepository repository;
    private final MartRefreshUseCase refresher;

    public ReconcileUseCase(MartOpsRepository repository, MartRefreshUseCase refresher) {
        this.repository = repository;
        this.refresher = refresher;
    }

    /**
     * The outcome of one reconcile pass.
     *
     * @param monthsChecked months present on either side, inside the ETL's scope
     * @param driftCount drifted MONTHS — zero on a healthy mart. Deliberately months and not
     *     rows: a single edited {@code custom_values} row shifts its month's checksum without
     *     changing any count, and there is no way to attribute a checksum delta back to a row
     *     count. One drifted month is one unit of repair, because {@code refreshMonth} is what
     *     repairs it.
     * @param missing fact rows the mirror has and the fact does not — a dropped Kafka event or
     *     a month the watermark never reached
     * @param extra fact rows with no mirror counterpart in scope. {@code refreshMonth} cannot
     *     clear these; {@link #pruneOutOfScope()} is what removes them
     * @param staleMonths months whose counts AGREE but whose checksums or config generation do
     *     not — the in-place-edit and config-drift cases
     * @param repaired rows actually rewritten by the re-merges this pass triggered
     */
    public record ReconcileResult(int monthsChecked, int driftCount, int missing, int extra,
            int staleMonths, int repaired) {

        public boolean clean() {
            return driftCount == 0;
        }
    }

    /**
     * Compares every in-scope month, repairs what drifted, and logs each month.
     *
     * <p>Both sides come from {@code incident-delta.sql} and therefore from ONE scope
     * predicate. See {@link MartOpsRepository} for why that is not a stylistic preference.
     */
    public ReconcileResult reconcile() {
        long startedAt = System.nanoTime();

        Map<String, MonthSummary> mirror = byMonth(repository.mirrorMonths());
        Map<String, MonthSummary> fact = byMonth(repository.factMonths());
        int generation = repository.cfgGeneration();

        // TreeSet: months are compared and logged in calendar order, so ETL_RECONCILE_LOG
        // reads as a timeline rather than as hash-map order.
        TreeSet<String> monthKeys = new TreeSet<>(mirror.keySet());
        monthKeys.addAll(fact.keySet());
        List<String> months = new ArrayList<>(monthKeys);

        int drifted = 0;
        int missing = 0;
        int extra = 0;
        int stale = 0;
        int repaired = 0;

        for (String month : months) {
            MonthSummary m = mirror.get(month);
            MonthSummary f = fact.get(month);
            int mirrorCount = m == null ? 0 : m.count();
            int factCount = f == null ? 0 : f.count();

            int monthMissing = Math.max(0, mirrorCount - factCount);
            int monthExtra = Math.max(0, factCount - mirrorCount);
            boolean checksumDrift = mirrorCount == factCount
                    && mirrorCount > 0
                    && !contentAgrees(m, f, generation);

            missing += monthMissing;
            extra += monthExtra;
            if (checksumDrift) {
                stale++;
            }

            boolean monthDrifted = monthMissing > 0 || monthExtra > 0 || checksumDrift;
            if (!monthDrifted) {
                repository.logReconcile(month, mirrorCount, factCount, 0, "clean");
                continue;
            }

            drifted++;
            String action;
            if (monthMissing > 0 || checksumDrift) {
                // Wholesale re-merge of the month. refresh() cannot do this: it is
                // watermark-driven and can never reach a row it already passed, nor one that
                // was never imported. refreshMonth() does NOT advance the watermark — it is a
                // repair, not progress.
                RefreshResult result = refresher.refreshMonth(month);
                repaired += result.changed();
                // REPAIRS ARE NEVER SILENT: the row count lands in the log table AND here.
                log.warn("reconcile REPAIRED month={} mirror={} fact={} missing={} "
                        + "checksumDrift={} rowsRewritten={} rowsRejected={}",
                        month, mirrorCount, factCount, monthMissing, checksumDrift,
                        result.changed(), result.rejected());
                action = "re-merged month: rewrote " + result.changed() + " rows";
            } else {
                // Extra-only: every fact row for this month has aged out of the window or left
                // tracker scope. A MERGE cannot delete, so this is pruneOutOfScope()'s job and
                // reconcile reports it rather than pretending to have fixed it.
                log.warn("reconcile DRIFT month={} mirror={} fact={} extra={} "
                        + "(not repairable by re-merge; pruneOutOfScope() owns this)",
                        month, mirrorCount, factCount, monthExtra);
                action = "extra rows only: " + monthExtra + " await pruneOutOfScope()";
            }
            repository.logReconcile(month, mirrorCount, factCount,
                    monthMissing + monthExtra + (checksumDrift ? 1 : 0), action);
        }

        ReconcileResult result = new ReconcileResult(months.size(), drifted, missing, extra,
                stale, repaired);
        long millis = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info("reconcile: months={} drift={} missing={} extra={} stale={} repaired={} "
                + "durationMs={}", result.monthsChecked(), result.driftCount(),
                result.missing(), result.extra(), result.staleMonths(), result.repaired(),
                millis);
        return result;
    }

    /**
     * Deletes mart rows the report no longer covers, and returns how many.
     *
     * <p>THE one sanctioned delete in this project. The no-tombstones rule ("deletes arrive as
     * status changes, never row removals") governs MIRROR data and Redmine's lifecycle; this
     * removes OUR OWN fact rows whose issue aged out of the rolling window, whose tracker left
     * {@code CFG_TRACKER_SCOPE}, or that vanished from the mirror. Without the carve-out the
     * mart permanently holds rows the delivered workbook never had.
     *
     * <p>Separate from {@link #reconcile()}'s repair path on purpose: {@code refreshMonth} is a
     * MERGE over a delta whose scope predicates EXCLUDE exactly these rows, so a MERGE
     * structurally cannot remove them.
     */
    public int pruneOutOfScope() {
        int pruned = repository.prune();
        String action = "pruned " + pruned + " out-of-scope rows";
        repository.logReconcile(null, 0, 0, pruned, action);
        if (pruned > 0) {
            // Counts only. The rows themselves carry production incident text.
            log.warn("prune: {} — fact rows outside CFG_TRACKER_SCOPE or the rolling window",
                    action);
        } else {
            log.info("prune: nothing out of scope");
        }
        return pruned;
    }

    /**
     * Content equality for a month whose counts already agree.
     *
     * <p>Three terms, and all three are load-bearing — they are the same three the MERGE's
     * content predicate uses, aggregated:
     *
     * <ul>
     *   <li>{@code rowChecksum} — the issue row moved;
     *   <li>{@code cvChecksum} — a custom value was edited in place, which touches nothing on
     *       {@code issues} and would otherwise be invisible forever;
     *   <li>{@code minGeneration} — a {@code CFG_*} edit superseded how these rows were
     *       derived, while both checksums still agree because the source never moved.
     * </ul>
     */
    private static boolean contentAgrees(MonthSummary mirror, MonthSummary fact,
            int generation) {
        if (mirror == null || fact == null) {
            return false;
        }
        return sameNumber(mirror.rowChecksum(), fact.rowChecksum())
                && sameNumber(mirror.cvChecksum(), fact.cvChecksum())
                && fact.minGeneration() != null
                && fact.minGeneration() >= generation;
    }

    /** {@code compareTo}, not {@code equals}: {@code 0} and {@code 0.0} are the same number. */
    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return left.compareTo(right) == 0;
    }

    private static Map<String, MonthSummary> byMonth(List<MonthSummary> summaries) {
        Map<String, MonthSummary> byMonth = new LinkedHashMap<>();
        for (MonthSummary summary : summaries) {
            if (summary.createMonth() != null) {
                byMonth.put(summary.createMonth(), summary);
            }
        }
        return byMonth;
    }
}
