package id.co.yokke.incidentdashboard.domain.usecase.etl;

import id.co.yokke.incidentdashboard.domain.usecase.label.EnumLabelResolver;
import id.co.yokke.incidentdashboard.domain.usecase.label.UserLabelResolver;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository.CustomFieldMapping;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository.CustomValue;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository.DeltaRow;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository.IssueCustomValues;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository.JournalProp;
import id.co.yokke.incidentdashboard.infrastructure.datasource.service.MartRefreshRepository.JournalPropMapping;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Watermark ETL: mirror delta in, {@code INCIDENT_FACT} out (Task 11).
 *
 * <h2>Why it chunks, and why the cursor is a keyset</h2>
 *
 * <p>The first run after deploy is a full-history load of roughly 23k issues. Doing it as one
 * pass inside one transaction on a 2-minute cron means the run overlaps its own successor, and
 * a failure leaves the watermark unmoved so the whole load retries forever. Instead the run
 * processes bounded chunks and commits + advances the watermark PER CHUNK, so a partial load is
 * a partial mart rather than an empty one.
 *
 * <p>{@code issues.updated_on} is {@code TIMESTAMP(0)} — second precision — so many rows share
 * one value. A timestamp-only cursor therefore either re-reads or skips a whole group; combined
 * with the per-run rewind below it does not terminate at all. The cursor is
 * {@code (UPDATED_ON, ID)}.
 *
 * <h2>Why the overlap is applied ONCE</h2>
 *
 * <p>A row committed at T−1s carrying {@code updated_on = T−3s} would be skipped forever by a
 * strict {@code > watermark}, so each RUN rewinds by
 * {@code incident.etl.watermark-overlap-seconds}. Applying that rewind to every CHUNK would
 * make the loop immortal, so it is folded into the starting cursor only.
 *
 * <p>The rewind also means the delta is never empty on a re-run, which is why {@code changed}
 * is counted from what the MERGE actually wrote and can never be {@code delta.size()}.
 */
@Service
@Lazy
public class MartRefreshUseCase {

    private static final Logger log = LoggerFactory.getLogger(MartRefreshUseCase.class);

    /**
     * The two fact columns whose ROW PRESENCE (not value) the report turns on, keyed by the
     * config's target column rather than by the Redmine custom field id.
     *
     * <p>Oracle cannot store the empty string, so "a custom_values row exists but holds nothing"
     * and "there is no row at all" are indistinguishable at the value level — and the source
     * report's SOLUTION classifier and AGING_NEW branch treat them differently. These two flags
     * are the discriminator that reproduces it.
     */
    private static final String CLOSE_DATE = "CLOSE_DATE";
    private static final String RESOLUTION_DATE = "RESOLUTION_DATE";

    /** Cursor id for the first chunk: every real issue id is greater. */
    private static final long BEFORE_FIRST_ID = -1L;

    private final MartRefreshRepository repository;
    private final EnumLabelResolver enumLabels;
    private final UserLabelResolver userLabels;
    private final TransactionTemplate transactions;
    private final int chunkSize;
    private final int overlapSeconds;

    /**
     * Non-reentrant: a slow run must not overlap its successor on the cron. A second caller is
     * turned away rather than queued — the next tick will pick the work up anyway.
     */
    private final ReentrantLock running = new ReentrantLock();

    public MartRefreshUseCase(
            MartRefreshRepository repository,
            EnumLabelResolver enumLabels,
            UserLabelResolver userLabels,
            TransactionTemplate transactions,
            @Value("${incident.etl.chunk-size:500}") int chunkSize,
            @Value("${incident.etl.watermark-overlap-seconds:300}") int overlapSeconds) {
        this.repository = repository;
        this.enumLabels = enumLabels;
        this.userLabels = userLabels;
        this.transactions = transactions;
        this.chunkSize = chunkSize;
        this.overlapSeconds = overlapSeconds;
    }

    /**
     * @param scanned rows the delta returned — INFLATED by the watermark overlap, so it is a
     *     progress figure, not an assertion target
     * @param changed rows the MERGE actually wrote. THIS is the number to assert on: it is zero
     *     when nothing in the source, the custom values or the config moved
     * @param rejected unparseable free-text dates quarantined in {@code INCIDENT_FACT_REJECT}
     * @param watermark the source timestamp the ETL has now reached
     */
    public record RefreshResult(int scanned, int changed, int rejected, Instant watermark) {
    }

    /** Incremental refresh from the persisted watermark. */
    public RefreshResult refresh() {
        return refresh(chunkSize);
    }

    /**
     * Incremental refresh with an explicit chunk size.
     *
     * <p>Exposed so an operator can throttle the first full-history load, and so
     * {@code MartRefreshIT} can drive a chunk boundary through a group of rows that share one
     * {@code UPDATED_ON} second — the case a timestamp-only cursor gets wrong.
     */
    public RefreshResult refresh(int size) {
        if (!running.tryLock()) {
            log.warn("mart refresh already running; skipping this tick");
            return new RefreshResult(0, 0, 0, instant(repository.readWatermark()));
        }
        try {
            LocalDateTime watermark = repository.readWatermark();
            // The rewind, applied to the STARTING cursor only. BEFORE_FIRST_ID so that every
            // row sharing the rewound second is included rather than half-skipped.
            LocalDateTime from = watermark.minusSeconds(overlapSeconds);
            return run(from, BEFORE_FIRST_ID, null, size, true);
        } finally {
            running.unlock();
        }
    }

    /**
     * Month-scoped, watermark-independent re-merge — Task 12's reconcile calls this to repair
     * drift (including a row left stale by a {@code CFG_*} edit the incremental scan never
     * revisited). It deliberately does NOT advance the watermark: it is a repair, not progress.
     *
     * @param createMonth {@code YYYYMM}, matched against {@code issues.created_on}
     */
    public RefreshResult refreshMonth(String createMonth) {
        if (!running.tryLock()) {
            log.warn("mart refresh already running; skipping refreshMonth({})", createMonth);
            return new RefreshResult(0, 0, 0, instant(repository.readWatermark()));
        }
        try {
            return run(LocalDateTime.of(1970, 1, 1, 0, 0), BEFORE_FIRST_ID, createMonth,
                    chunkSize, false);
        } finally {
            running.unlock();
        }
    }

    // ------------------------------------------------------------------- the chunk loop

    private RefreshResult run(LocalDateTime fromTs, long fromId, String month, int size,
            boolean advanceWatermark) {
        // One snapshot per RUN, not per row: the label tables are small and a mid-run reload
        // would make two rows of the same chunk disagree.
        enumLabels.refresh();
        userLabels.refresh();

        DerivedFields.SlaPolicy slaPolicy = slaPolicy(repository.slaPolicy());
        List<CustomFieldMapping> customFields = repository.customFieldMap();
        List<JournalPropMapping> journalProps = repository.journalPropMap();
        int generation = repository.cfgGeneration();

        LocalDateTime cursorTs = fromTs;
        long cursorId = fromId;
        int scanned = 0;
        int changed = 0;
        int rejected = 0;

        while (true) {
            final LocalDateTime ts = cursorTs;
            final long id = cursorId;
            List<DeltaRow> delta = repository.selectDelta(ts, id, month, size);
            if (delta.isEmpty()) {
                break;
            }

            ChunkResult result = transactions.execute(status ->
                    mergeChunk(delta, slaPolicy, customFields, journalProps, generation));
            scanned += delta.size();
            changed += result.changed();
            rejected += result.rejected();

            DeltaRow last = delta.get(delta.size() - 1);
            cursorTs = last.cursorTs();
            cursorId = last.noIncident();

            if (advanceWatermark) {
                // Per chunk, so a run killed half way keeps the progress it made.
                repository.advanceWatermark(cursorTs, "OK", changed);
            }
            if (delta.size() < size) {
                break;
            }
        }

        if (advanceWatermark) {
            // Records the run even when it scanned nothing. GREATEST inside the MERGE stops
            // this from rewinding the watermark to the overlap-adjusted start.
            repository.advanceWatermark(cursorTs, "OK", changed);
        }
        log.info("mart refresh: scanned={} changed={} rejected={} closeBasisUnknown={} "
                + "watermark={}", scanned, changed, rejected, closeBasisUnknown(), cursorTs);
        return new RefreshResult(scanned, changed, rejected, instant(repository.readWatermark()));
    }

    private record ChunkResult(int changed, int rejected) {
    }

    /** {@code CFG_SLA_POLICY}'s rows as the pure-function policy {@link DerivedFields} reads. */
    private static DerivedFields.SlaPolicy slaPolicy(
            Map<Long, MartRefreshRepository.SlaPolicyEntry> entries) {
        Map<Long, Integer> days = new HashMap<>();
        Map<Long, Integer> cushions = new HashMap<>();
        entries.forEach((priorityId, entry) -> {
            days.put(priorityId, entry.slaDays());
            cushions.put(priorityId, entry.atRiskRemainingDays());
        });
        return DerivedFields.SlaPolicy.of(days, cushions);
    }

    /**
     * Closed rows with no usable stop timestamp, for the summary line. Diagnostic only: a failure
     * to count must not turn a refresh that already committed its chunks into a failed one, so
     * it degrades to {@code -1} (and a WARN) instead of throwing.
     */
    private long closeBasisUnknown() {
        try {
            return repository.closeBasisUnknownCount();
        } catch (RuntimeException e) {
            log.warn("mart refresh: could not count closeBasisUnknown rows", e);
            return -1L;
        }
    }

    private ChunkResult mergeChunk(List<DeltaRow> delta, DerivedFields.SlaPolicy slaPolicy,
            List<CustomFieldMapping> customFields, List<JournalPropMapping> journalProps,
            int generation) {
        List<Long> ids = delta.stream().map(DeltaRow::noIncident).toList();
        // custom_values carries no timestamp and is UPDATEd in place, so it cannot be deltaed
        // on its own: re-read ALL values for every issue in the chunk. The chunk is bounded;
        // the alternative is missing edits entirely.
        Map<Long, IssueCustomValues> customValues = repository.loadCustomValues(ids);
        Map<Long, List<JournalProp>> props = repository.loadJournalProps(ids);

        List<Map<String, Object>> factRows = new ArrayList<>(delta.size());
        Map<Long, List<Object[]>> rejects = new LinkedHashMap<>();
        int rejected = 0;

        for (DeltaRow row : delta) {
            List<Object[]> rowRejects = new ArrayList<>();
            factRows.add(toFactRow(row,
                    customValues.get(row.noIncident()),
                    props.getOrDefault(row.noIncident(), List.of()),
                    slaPolicy, customFields, journalProps, generation, rowRejects));
            rejects.put(row.noIncident(), rowRejects);
            rejected += rowRejects.size();
        }

        int changed = repository.merge(factRows);
        // Written for EVERY scanned incident, including the ones with no rejects: that is what
        // clears a quarantine after the value is corrected in Redmine.
        rejects.forEach(repository::saveRejects);
        return new ChunkResult(changed, rejected);
    }

    // ------------------------------------------------------------------- row assembly

    private Map<String, Object> toFactRow(DeltaRow row, IssueCustomValues values,
            List<JournalProp> props, DerivedFields.SlaPolicy slaPolicy,
            List<CustomFieldMapping> customFields, List<JournalPropMapping> journalProps,
            int generation, List<Object[]> rejects) {

        Map<String, Object> fact = new HashMap<>();
        fact.put("NO_INCIDENT", row.noIncident());
        fact.put("TRACKER_ID", row.trackerId());
        fact.put("SUBJECT", row.subject());
        fact.put("DATE_CREATE", row.dateCreate());
        fact.put("CREATE_MONTH", row.createMonth() != null
                ? row.createMonth()
                : DerivedFields.createMonth(row.dateCreate()));
        fact.put("UPDATED_ON", row.updatedOn());
        fact.put("CLOSED_ON", row.closedOn());
        fact.put("STATUS_ID", row.statusId());
        fact.put("PRIORITY_ID", row.priorityId());
        fact.put("SLA_DAYS", DerivedFields.slaDays(row.priorityId(), slaPolicy));
        // Resolved next to SLA_DAYS, from the same policy row, so the view needs no config join
        // and the unknown-priority default is decided in one place (DerivedFields).
        fact.put("SLA_AT_RISK_DAYS", DerivedFields.slaAtRiskDays(row.priorityId(), slaPolicy));
        fact.put("PARENT_ID", row.parentId());
        fact.put("DONE_RATIO", row.doneRatio());

        fact.put("AUTHOR_ID", row.authorId());
        fact.put("AUTHOR", userLabels.authorLabel(row.authorId()));
        fact.put("ASSIGNED_TO_ID", row.assignedToId());
        fact.put("ASSIGNED_TO", userLabels.assigneeLabel(row.assignedToId()));

        fact.put("LAST_STATUS", row.lastStatus());
        fact.put("LAST_DATE_STATUS", row.lastDateStatus());
        fact.put("FIRST_STATUS_CHANGE_AT", row.firstStatusChangeAt());

        applyCustomValues(row, values, customFields, fact, rejects);
        applyJournalProps(props, journalProps, fact);

        fact.put("HAS_DATE_REJECT", rejects.isEmpty() ? 0 : 1);
        fact.put("ETL_UPDATED_AT", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        fact.put("SOURCE_UPDATED_ON", row.cursorTs());
        fact.put("CV_DIGEST", values == null ? BigDecimal.ZERO : values.digest());
        fact.put("CFG_GENERATION", generation);
        return fact;
    }

    private void applyCustomValues(DeltaRow row, IssueCustomValues values,
            List<CustomFieldMapping> customFields, Map<String, Object> fact,
            List<Object[]> rejects) {

        Map<String, CustomValue> byTarget = new HashMap<>();
        if (values != null) {
            for (CustomValue value : values.values()) {
                byTarget.put(value.targetColumn(), value);
            }
        }

        for (CustomFieldMapping mapping : customFields) {
            String target = mapping.targetColumn();
            CustomValue value = byTarget.get(target);
            switch (mapping.fieldKind()) {
                case "ENUM" -> {
                    Long enumValueId = enumValueId(value);
                    fact.put(target + "_ID", enumValueId);
                    // An absent value resolves to Unclassified and an unresolvable one to
                    // "Unknown (<id>)" -- two DIFFERENT facts, and neither may be a NULL that
                    // silently drops the row out of an aggregate.
                    boolean unreadable =
                            enumValueId == null && value != null && !value.raw().isBlank();
                    fact.put(target, unreadable
                            ? "Unknown (" + value.raw() + ")"
                            : enumLabels.resolve(mapping.customFieldId(), enumValueId));
                }
                case "DATE" -> {
                    DerivedFields.DateParse parsed =
                            DerivedFields.parseRedmineDate(value == null ? null : value.raw());
                    fact.put(target, parsed.value());
                    if (parsed.rejected()) {
                        // Quarantine, NOT a silent null -- and the incident still lands in the
                        // fact with the date NULL, because "we could not read this" must not
                        // remove an incident from the report.
                        rejects.add(new Object[] {mapping.customFieldId(), mapping.logicalName(),
                                truncate(value.raw()), parsed.reason()});
                    }
                }
                default -> fact.put(target, value == null ? null : value.raw());
            }
        }

        // ROW PRESENCE, not the value: this is what carries MySQL's NULL-vs-'' distinction on
        // an Oracle that cannot store the empty string.
        boolean hasClose = byTarget.containsKey(CLOSE_DATE);
        boolean hasResolution = byTarget.containsKey(RESOLUTION_DATE);
        fact.put("HAS_CLOSE_DATE_ROW", hasClose ? 1 : 0);
        fact.put("HAS_RESOLUTION_ROW", hasResolution ? 1 : 0);
        // "" when the row exists, null when it does not -- exactly the two inputs
        // DerivedFields.solution distinguishes, and the reason it takes raw Strings.
        fact.put("SOLUTION", DerivedFields.solution(
                hasClose ? byTarget.get(CLOSE_DATE).raw() : null,
                hasResolution ? byTarget.get(RESOLUTION_DATE).raw() : null));
        log.trace("incident {} close-row={} resolution-row={}", row.noIncident(), hasClose,
                hasResolution);
    }

    private void applyJournalProps(List<JournalProp> props,
            List<JournalPropMapping> journalProps, Map<String, Object> fact) {
        Map<String, JournalProp> byTarget = new HashMap<>();
        for (JournalProp prop : props) {
            byTarget.put(prop.targetColumn(), prop);
        }
        for (JournalPropMapping mapping : journalProps) {
            String target = mapping.targetColumn();
            JournalProp prop = byTarget.get(target);
            String raw = prop == null ? null : prop.valueText();
            if ("USER".equals(mapping.valueKind())) {
                Long userId = parseLong(raw);
                fact.put(target + "_ID", userId);
                fact.put(target, userLabels.developerLabel(userId));
            } else {
                fact.put(target, raw);
            }
        }
    }

    private static Long enumValueId(CustomValue value) {
        return value == null ? null : parseLong(value.valueText());
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code INCIDENT_FACT_REJECT.RAW_VALUE} is VARCHAR2(400 CHAR). */
    private static String truncate(String raw) {
        return raw != null && raw.length() > 400 ? raw.substring(0, 400) : raw;
    }

    /**
     * Mirror timestamps are offset-naive MySQL wall clock and the whole mart is pinned to one
     * timezone (Global Constraints), so UTC here is a lossless re-labelling, not a conversion.
     */
    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
