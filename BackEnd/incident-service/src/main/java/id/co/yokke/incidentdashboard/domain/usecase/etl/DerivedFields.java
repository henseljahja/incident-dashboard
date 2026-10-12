package id.co.yokke.incidentdashboard.domain.usecase.etl;

import id.co.yokke.incidentdashboard.domain.model.SolutionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.Objects;

/**
 * Write-time derived fields for {@code INCIDENT_FACT} (Task 10).
 *
 * <p>Pure functions only — no Spring, no database access. Read-time values that depend on
 * {@code NOW()} ({@code AGING_*}, {@code SLA_RATIO}/{@code SLA_BREACHED}/{@code SLA_BUCKET})
 * live in {@code V_INCIDENT} (see {@code V3__mart.sql}) and are deliberately NOT reproduced
 * here: materialising them would make every row stale the instant it is written.
 */
public final class DerivedFields {

    private static final Logger log = LoggerFactory.getLogger(DerivedFields.class);

    /**
     * The source query's {@code ELSE} branch for an unrecognised priority id.
     *
     * <p>Public because {@code GET /api/metrics/definitions} echoes it: the ⓘ text must state the
     * number the ETL actually applies, not a copy of it in a translation file.
     */
    public static final int UNKNOWN_PRIORITY_SLA_DAYS = 3;

    /**
     * The at-risk cushion for an unrecognised priority id: the Critical cushion, paired with the
     * strictest SLA above. Matches the {@code INCIDENT_FACT.SLA_AT_RISK_DAYS} column default.
     */
    public static final int UNKNOWN_PRIORITY_AT_RISK_DAYS = 1;

    /**
     * {@code close_date}/{@code resolution_date} format observed in both the sample dump
     * ({@code docs/reference/05. redmine.custom_values.sql}) and the hand-authored
     * {@code custom_values.csv} fixture: {@code YYYY-MM-DD}, e.g. {@code 2026-07-01}. No other
     * format appears in either source. {@code ResolverStyle.STRICT} with the {@code uuuu}
     * (not {@code yyyy}) pattern rejects calendar-invalid dates such as {@code 2025-02-31}
     * instead of silently rolling them into March.
     */
    private static final DateTimeFormatter REDMINE_DATE_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter CREATE_MONTH_FORMAT = DateTimeFormatter.ofPattern("uuuuMM");

    private DerivedFields() {
    }

    /**
     * A configured SLA ladder, keyed on {@code PRIORITY_ID} (not the priority name — names are
     * Redmine-admin-editable; see {@link #slaDays(Long, SlaPolicy)}).
     *
     * <p>Callers pass in whatever {@code CFG_SLA_POLICY} currently holds; this class never
     * queries the database itself.
     */
    @FunctionalInterface
    public interface SlaPolicy {

        /**
         * @param priorityId the Redmine priority id, may be {@code null}
         * @return the configured SLA days for {@code priorityId}, or {@code null} if none is
         *     configured (including when {@code priorityId} itself is {@code null})
         */
        Integer slaDaysFor(Long priorityId);

        /**
         * The at-risk cushion ({@code CFG_SLA_POLICY.AT_RISK_REMAINING_DAYS}) for
         * {@code priorityId}. Defaults to "none configured" so a days-only policy still resolves
         * through {@link #slaAtRiskDays(Long, SlaPolicy)}'s fallback.
         *
         * @return the configured cushion, or {@code null} if none is configured
         */
        default Integer atRiskDaysFor(Long priorityId) {
            return null;
        }

        /** Builds a {@link SlaPolicy} backed by a plain id-to-days map, for tests and seeding. */
        static SlaPolicy of(Map<Long, Integer> daysByPriorityId) {
            Objects.requireNonNull(daysByPriorityId, "daysByPriorityId");
            // Map.of(...) (and other common immutable-map builders) reject a null key on
            // get(); a null priorityId must fall through to the ELSE default like any other
            // unrecognised id, not throw.
            return priorityId -> priorityId == null ? null : daysByPriorityId.get(priorityId);
        }

        /**
         * Builds a {@link SlaPolicy} carrying both the SLA days and the at-risk cushion per
         * priority id — the shape {@code CFG_SLA_POLICY} has since V9.
         */
        static SlaPolicy of(Map<Long, Integer> daysByPriorityId,
                Map<Long, Integer> atRiskDaysByPriorityId) {
            Objects.requireNonNull(daysByPriorityId, "daysByPriorityId");
            Objects.requireNonNull(atRiskDaysByPriorityId, "atRiskDaysByPriorityId");
            return new SlaPolicy() {
                @Override
                public Integer slaDaysFor(Long priorityId) {
                    return priorityId == null ? null : daysByPriorityId.get(priorityId);
                }

                @Override
                public Integer atRiskDaysFor(Long priorityId) {
                    return priorityId == null ? null : atRiskDaysByPriorityId.get(priorityId);
                }
            };
        }
    }

    /**
     * The result of parsing a free-text custom-field date value.
     *
     * <p>{@code null}/{@code ""} both mean "no date" ({@code rejected == false}, {@code value
     * == null}) — that is the only distinction this method makes. It does NOT carry the
     * separate NULL-vs-empty-string distinction that {@link #solution(String, String)} needs;
     * that is carried by the {@code HAS_*_ROW} flags at the caller (Task 11), never recovered
     * from this result.
     *
     * <p>Anything present but unparseable is {@code rejected == true} with {@code value ==
     * null} and a human-readable {@code reason}, so Task 11 can route it to
     * {@code INCIDENT_FACT_REJECT} instead of silently nulling an SLA-relevant date.
     */
    public record DateParse(LocalDate value, boolean rejected, String reason) {

        private static final DateParse ABSENT = new DateParse(null, false, null);

        static DateParse absent() {
            return ABSENT;
        }

        static DateParse rejected(String reason) {
            return new DateParse(null, true, reason);
        }

        static DateParse parsed(LocalDate value) {
            return new DateParse(value, false, null);
        }
    }

    /**
     * Resolves the SLA day count for {@code priorityId} from {@code policy}
     * ({@code CFG_SLA_POLICY}).
     *
     * <p>Keyed on the id, never a Java switch on the priority name: a Redmine-admin rename of
     * a priority must not silently move incidents between SLA buckets. An id absent from the
     * policy (including a {@code null} priority id) falls back to the source query's
     * {@code ELSE} value, {@value #UNKNOWN_PRIORITY_SLA_DAYS}, and is logged at WARN — an
     * unrecognised priority quietly taking the strictest bucket is a number nobody could
     * otherwise explain later.
     */
    public static int slaDays(Long priorityId, SlaPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Integer configured = policy.slaDaysFor(priorityId);
        if (configured != null) {
            return configured;
        }
        log.warn("No CFG_SLA_POLICY entry for PRIORITY_ID={}; defaulting to the strictest SLA "
                + "of {} days", priorityId, UNKNOWN_PRIORITY_SLA_DAYS);
        return UNKNOWN_PRIORITY_SLA_DAYS;
    }

    /**
     * Resolves the at-risk cushion — how many calendar days before the SLA runs out an OPEN
     * ticket starts counting as At Risk — for {@code priorityId} from {@code policy}.
     *
     * <p>Resolved at ETL time next to {@link #slaDays(Long, SlaPolicy)} and stored on the fact
     * as {@code SLA_AT_RISK_DAYS}, so {@code V_INCIDENT} needs no config join and the
     * unknown-priority default lives in exactly one language. An id with no cushion configured
     * (including a {@code null} id) falls back to {@value #UNKNOWN_PRIORITY_AT_RISK_DAYS} and is
     * logged at WARN, mirroring {@code slaDays}. The log line carries the priority id only.
     */
    public static int slaAtRiskDays(Long priorityId, SlaPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Integer configured = policy.atRiskDaysFor(priorityId);
        if (configured != null) {
            return configured;
        }
        log.warn("No CFG_SLA_POLICY at-risk cushion for PRIORITY_ID={}; defaulting to {} day(s)",
                priorityId, UNKNOWN_PRIORITY_AT_RISK_DAYS);
        return UNKNOWN_PRIORITY_AT_RISK_DAYS;
    }

    /**
     * Classifies {@code close}/{@code res} (the raw {@code close_date}/{@code resolution_date}
     * custom-field text) exactly as the source report's {@code CASE} expression does — see
     * {@code docs/reference/Query Detail Redmine Incident, Problem, SR.sql}:
     *
     * <pre>
     * case when e.close_date is null and f.resolution_date is null
     *        or e.close_date = '' and f.resolution_date = '' then null
     *      when e.close_date is null and f.resolution_date is not null
     *        or e.close_date = '' and f.resolution_date &lt;&gt; '' then 'Workaround Solution'
     *      when f.resolution_date is null and e.close_date is not null
     *        or f.resolution_date = '' and e.close_date &lt;&gt; ''
     *        or e.close_date = f.resolution_date then 'Permanent Solution'
     *      when f.resolution_date &lt;&gt; '' and e.close_date &gt; f.resolution_date
     *        then 'Workaround &amp; Pemanent Solution'
     * end
     * </pre>
     *
     * <p>MUST take raw strings — {@code null} and {@code ""} are NOT interchangeable inputs
     * here (SQL {@code x = ''} is never true when {@code x IS NULL}), and a
     * {@code LocalDate}-typed signature cannot represent that. This deliberately reproduces
     * the source's asymmetric quirk: {@code solution(null, "")} is {@code "Workaround
     * Solution"} while {@code solution("", null)} is {@code "Permanent Solution"}.
     *
     * @return one of the {@link SolutionType} constants, or {@code null} when no branch
     *     matches (including {@code close < res}, which the source has no branch for)
     */
    public static String solution(String close, String res) {
        boolean closeNull = close == null;
        boolean resNull = res == null;
        boolean closeEmpty = !closeNull && close.isEmpty();
        boolean resEmpty = !resNull && res.isEmpty();
        boolean closeNotEmpty = !closeNull && !closeEmpty;
        boolean resNotEmpty = !resNull && !resEmpty;

        if ((closeNull && resNull) || (closeEmpty && resEmpty)) {
            return null;
        }
        if ((closeNull && !resNull) || (closeEmpty && resNotEmpty)) {
            return SolutionType.WORKAROUND;
        }
        if ((resNull && !closeNull) || (resEmpty && closeNotEmpty) || (closeNotEmpty && resNotEmpty && close.equals(res))) {
            return SolutionType.PERMANENT;
        }
        if (resNotEmpty && closeNotEmpty && close.compareTo(res) > 0) {
            return SolutionType.WORKAROUND_AND_PERMANENT;
        }
        return null;
    }

    /** {@code YYYYMM} grouping key derived from the issue's creation timestamp. */
    public static String createMonth(LocalDateTime createdOn) {
        Objects.requireNonNull(createdOn, "createdOn");
        return createdOn.format(CREATE_MONTH_FORMAT);
    }

    /**
     * Parses a free-text custom-field date ({@code close_date} cf 105, {@code resolution_date}
     * cf 106, {@code migration_date} cf 110) in the {@code YYYY-MM-DD} format observed in the
     * data.
     *
     * <p>{@code null} and {@code ""} both mean "no date" and parse to {@link DateParse#absent()}
     * ({@code rejected == false}). Anything else that does not parse as a calendar-valid
     * {@code YYYY-MM-DD} date (garbage text, or a date like {@code 31/02/2025} that
     * {@link ResolverStyle#STRICT} rejects) comes back {@code rejected == true} with a
     * {@code null} value — a quarantine candidate for {@code INCIDENT_FACT_REJECT}, never a
     * silent null.
     */
    public static DateParse parseRedmineDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DateParse.absent();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return DateParse.absent();
        }
        try {
            return DateParse.parsed(LocalDate.parse(trimmed, REDMINE_DATE_FORMAT));
        } catch (DateTimeException e) {
            return DateParse.rejected("unparseable date '" + raw + "': " + e.getMessage());
        }
    }
}
