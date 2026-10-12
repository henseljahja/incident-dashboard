package id.co.yokke.incidentdashboard.infrastructure.datasource.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The mart's write path: reads the delta out of the Redmine mirror and MERGEs it into
 * {@code INCIDENT_FACT} (Task 11).
 *
 * <h2>The MERGE column list is GENERATED, never typed</h2>
 *
 * <p>{@link #mergeSql()} builds its column list from {@code USER_TAB_COLUMNS} at first use.
 * This is the single-source-of-truth enforcer for the whole plan: {@code V3__mart.sql} is the
 * only place the mart's columns are enumerated, and a column added there is written by the
 * MERGE without anybody editing this class. {@code MartSchemaIT} asserts the two agree in both
 * directions, so a column can neither be silently dropped nor silently left unwritten.
 *
 * <p>The casts in the {@code USING} clause are generated from the same catalog rows. They are
 * not decoration: an untyped {@code ?} in a {@code SELECT ... FROM DUAL} gives Oracle nothing
 * to infer a type from, and the MERGE fails with ORA-00932 on the first CLOB or TIMESTAMP
 * column.
 *
 * <h2>The three-term content predicate</h2>
 *
 * <p>{@code WHEN MATCHED ... WHERE} compares three inputs and updates only if one moved:
 * {@code SOURCE_UPDATED_ON}, {@code CV_DIGEST} and {@code CFG_GENERATION}. All three are
 * required.
 *
 * <ul>
 *   <li>{@code DECODE}, not {@code <>}: {@code <>} is not NULL-safe, and every one of these
 *       can legitimately be NULL on an existing row.
 *   <li>{@code CV_DIGEST} catches a custom-value edit, which does not move
 *       {@code issues.updated_on} at all.
 *   <li>{@code CFG_GENERATION} is what makes the CFG_* tables <em>effective</em> rather than
 *       merely readable: a config edit changes neither of the first two inputs, so without it
 *       an already-merged row could never be re-derived and the mart would sit permanently and
 *       invisibly half-mapped.
 * </ul>
 *
 * <p>Because the predicate excludes unchanged rows, {@code executeUpdate()} returns 0 for them
 * — which is exactly how {@code changed} is counted. The watermark's 300s overlap means the
 * delta is never empty on a re-run, so {@code changed} can never be {@code delta.size()}.
 */
@Repository
@Lazy
public class MartRefreshRepository {

    private static final Logger log = LoggerFactory.getLogger(MartRefreshRepository.class);

    /** The mart's own primary key. A table identity, not a Redmine id. */
    public static final String KEY_COLUMN = "NO_INCIDENT";

    public static final String WATERMARK_KEY = "incident_fact";

    private static final String FACT_TABLE = "INCIDENT_FACT";

    private static final String DELTA_SQL_RESOURCE = "sql/incident-delta.sql";

    /**
     * Cold start. {@code resetAll()} truncates {@code ETL_WATERMARK} before every integration
     * test, and a fresh production schema has never run, so "no row" must mean "never ran"
     * rather than an error — see {@link #readWatermark()}.
     */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    private final JdbcTemplate jdbc;
    private final Map<String, String> sections;

    /** Built once from the catalog, on first use. */
    private volatile MergeStatement merge;

    public MartRefreshRepository(
            JdbcTemplate jdbc, @Value("${incident.mirror.schema}") String mirrorSchema) {
        this.jdbc = jdbc;
        // mirrorSchema carries its own trailing separator (a dot in prod, an underscore in
        // tests) -- concatenate, never add one.
        this.sections = loadSections(mirrorSchema);
    }

    // ------------------------------------------------------------------ delta read

    /** One in-scope issue, straight off {@code incident-delta.sql}. */
    public record DeltaRow(
            long noIncident,
            long trackerId,
            String subject,
            LocalDateTime dateCreate,
            String createMonth,
            LocalDateTime updatedOn,
            LocalDateTime cursorTs,
            LocalDateTime closedOn,
            long statusId,
            Long priorityId,
            Long parentId,
            Integer doneRatio,
            Long authorId,
            Long assignedToId,
            LocalDateTime firstStatusChangeAt,
            LocalDateTime lastDateStatus,
            String lastStatus) {
    }

    /** One {@code custom_values} row, already joined to {@code CFG_CUSTOM_FIELD_MAP}. */
    public record CustomValue(
            long customFieldId,
            String logicalName,
            String targetColumn,
            String fieldKind,
            String valueText,
            long valueLength) {

        /**
         * The raw text {@code DerivedFields.solution} and {@code parseRedmineDate} expect.
         *
         * <p>A row that exists but holds no readable text is {@code ""}, NOT {@code null}:
         * {@code null} is reserved for "no custom_values row at all". Oracle cannot store the
         * empty string, so the row's mere presence is what carries the distinction.
         */
        public String raw() {
            return valueText != null ? valueText : "";
        }
    }

    /** Every custom value of one issue, plus the digest over ALL of them. */
    public record IssueCustomValues(List<CustomValue> values, java.math.BigDecimal digest) {
    }

    /** The latest value of one mapped journal prop for one issue. */
    public record JournalProp(String logicalName, String targetColumn, String valueKind,
            String valueText) {
    }

    /**
     * One chunk of the delta, from the keyset cursor {@code (afterTs, afterId)} forward.
     *
     * @param month {@code YYYYMM} to restrict to (the watermark-independent repair path), or
     *     {@code null} for every month in the report window
     */
    public List<DeltaRow> selectDelta(LocalDateTime afterTs, long afterId, String month,
            int limit) {
        return jdbc.query(section("delta"), ps -> {
            Timestamp ts = Timestamp.valueOf(afterTs);
            ps.setTimestamp(1, ts);
            ps.setTimestamp(2, ts);
            ps.setLong(3, afterId);
            if (month == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, month);
            }
            ps.setInt(5, limit);
        }, (rs, rowNum) -> new DeltaRow(
                rs.getLong("NO_INCIDENT"),
                rs.getLong("TRACKER_ID"),
                rs.getString("SUBJECT"),
                localDateTime(rs, "DATE_CREATE"),
                rs.getString("CREATE_MONTH"),
                localDateTime(rs, "UPDATED_ON"),
                localDateTime(rs, "CURSOR_TS"),
                localDateTime(rs, "CLOSED_ON"),
                rs.getLong("STATUS_ID"),
                nullableLong(rs, "PRIORITY_ID"),
                nullableLong(rs, "PARENT_ID"),
                nullableInt(rs, "DONE_RATIO"),
                nullableLong(rs, "AUTHOR_ID"),
                nullableLong(rs, "ASSIGNED_TO_ID"),
                localDateTime(rs, "FIRST_STATUS_CHANGE_AT"),
                localDateTime(rs, "LAST_DATE_STATUS"),
                rs.getString("LAST_STATUS")));
    }

    /** All custom values (and the digest) for every issue in the chunk, in one round trip. */
    public Map<Long, IssueCustomValues> loadCustomValues(Collection<Long> issueIds) {
        Map<Long, List<CustomValue>> values = new LinkedHashMap<>();
        Map<Long, java.math.BigDecimal> digests = new HashMap<>();
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(issueIds);
        jdbc.query(inList(section("custom-values"), ids.size()), ps -> bindIds(ps, ids), rs -> {
            long issueId = rs.getLong("CUSTOMIZED_ID");
            digests.put(issueId, rs.getBigDecimal("CV_DIGEST"));
            String targetColumn = rs.getString("TARGET_COLUMN");
            if (targetColumn == null) {
                // Unmapped custom field: it still contributed to CV_DIGEST above (that is why
                // the join is a LEFT join), but there is no column to write it to.
                return;
            }
            values.computeIfAbsent(issueId, id -> new ArrayList<>()).add(new CustomValue(
                    rs.getLong("CUSTOM_FIELD_ID"),
                    rs.getString("LOGICAL_NAME"),
                    targetColumn,
                    rs.getString("FIELD_KIND"),
                    rs.getString("VALUE_TEXT"),
                    rs.getLong("VALUE_LEN")));
        });

        Map<Long, IssueCustomValues> byIssue = new LinkedHashMap<>();
        for (Long id : digests.keySet()) {
            byIssue.put(id, new IssueCustomValues(
                    values.getOrDefault(id, List.of()), digests.get(id)));
        }
        return byIssue;
    }

    /** The latest value of each mapped journal prop, for every issue in the chunk. */
    public Map<Long, List<JournalProp>> loadJournalProps(Collection<Long> issueIds) {
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(issueIds);
        Map<Long, List<JournalProp>> byIssue = new LinkedHashMap<>();
        // Braces matter: a value-compatible lambda here is ambiguous between JdbcTemplate's
        // RowCallbackHandler and ResultSetExtractor overloads and will not compile.
        jdbc.query(inList(section("journal-props"), ids.size()), ps -> bindIds(ps, ids), rs -> {
            byIssue.computeIfAbsent(rs.getLong("JOURNALIZED_ID"), id -> new ArrayList<>())
                    .add(new JournalProp(
                            rs.getString("LOGICAL_NAME"),
                            rs.getString("TARGET_COLUMN"),
                            rs.getString("VALUE_KIND"),
                            rs.getString("VALUE_TEXT")));
        });
        return byIssue;
    }

    // ------------------------------------------------------------------ config read

    /** One {@code CFG_SLA_POLICY} row: the SLA budget and the at-risk cushion (V9), in days. */
    public record SlaPolicyEntry(int slaDays, int atRiskRemainingDays) {
    }

    /**
     * {@code CFG_SLA_POLICY} as a priority-id-keyed ladder. Never keyed on the name.
     *
     * <p>Both numbers come off the SAME row, so a priority can never have an SLA without a
     * cushion or the reverse.
     */
    public Map<Long, SlaPolicyEntry> slaPolicy() {
        Map<Long, SlaPolicyEntry> policy = new HashMap<>();
        jdbc.query("SELECT PRIORITY_ID, SLA_DAYS, AT_RISK_REMAINING_DAYS FROM CFG_SLA_POLICY",
                rs -> {
                    policy.put(rs.getLong(1), new SlaPolicyEntry(rs.getInt(2), rs.getInt(3)));
                });
        return policy;
    }

    /**
     * How many mart rows are closed with no usable stop timestamp ({@code CLOSE_BASIS =
     * 'UNKNOWN'} on {@code V_INCIDENT}). A count only -- it surfaces the data-quality cohort in
     * the refresh summary without a single row leaving the database.
     */
    public long closeBasisUnknownCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_INCIDENT WHERE CLOSE_BASIS = 'UNKNOWN'", Long.class);
        return count == null ? 0L : count;
    }

    /** One active {@code CFG_CUSTOM_FIELD_MAP} row. */
    public record CustomFieldMapping(long customFieldId, String logicalName, String targetColumn,
            String fieldKind) {
    }

    /** One active non-STATUS {@code CFG_JOURNAL_PROP_MAP} row. */
    public record JournalPropMapping(String logicalName, String targetColumn, String valueKind) {
    }

    /**
     * The active custom-field mappings.
     *
     * <p>Needed in FULL, not just for the fields an issue happens to have rows for: an enum
     * field with no {@code custom_values} row must still resolve to {@code Unclassified} rather
     * than to a NULL that vanishes from the heatmap.
     */
    public List<CustomFieldMapping> customFieldMap() {
        return jdbc.query("SELECT CUSTOM_FIELD_ID, LOGICAL_NAME, TARGET_COLUMN, FIELD_KIND "
                        + "FROM CFG_CUSTOM_FIELD_MAP WHERE IS_ACTIVE = 1 ORDER BY CUSTOM_FIELD_ID",
                (rs, rowNum) -> new CustomFieldMapping(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
    }

    /** The active journal-prop mappings, minus the STATUS one the delta query handles itself. */
    public List<JournalPropMapping> journalPropMap() {
        return jdbc.query("SELECT LOGICAL_NAME, TARGET_COLUMN, VALUE_KIND "
                        + "FROM CFG_JOURNAL_PROP_MAP "
                        + "WHERE IS_ACTIVE = 1 AND VALUE_KIND <> 'STATUS' ORDER BY PROP_KEY",
                (rs, rowNum) -> new JournalPropMapping(rs.getString(1), rs.getString(2),
                        rs.getString(3)));
    }

    /**
     * The current config generation. Bumped by a trigger on every {@code CFG_*} write
     * (V1__config_tables.sql), stamped onto every fact row, and the third term of the MERGE's
     * content predicate.
     */
    public int cfgGeneration() {
        Integer generation = jdbc.queryForObject(
                "SELECT NVL(MAX(GENERATION), 0) FROM CFG_VERSION", Integer.class);
        return generation == null ? 0 : generation;
    }

    // ------------------------------------------------------------------- watermark

    /**
     * The last source timestamp this ETL reached, or {@link #EPOCH} when it has never run.
     *
     * <p>{@code MAX(...)} over zero rows, deliberately: a bare {@code SELECT ... INTO} throws
     * {@code NO_DATA_FOUND}/{@code EmptyResultDataAccessException} whenever the row is absent,
     * and the row IS absent in every integration test (the fixture loader truncates
     * {@code ETL_WATERMARK}) and on a freshly migrated schema. An absent row means COLD START,
     * not an error.
     */
    public LocalDateTime readWatermark() {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(LAST_SOURCE_TS) FROM ETL_WATERMARK WHERE WATERMARK_KEY = ?",
                Timestamp.class, WATERMARK_KEY);
        return ts == null ? EPOCH : ts.toLocalDateTime();
    }

    /**
     * Advances the watermark, per chunk, so a failed run keeps the progress it made.
     *
     * <p>{@code GREATEST} guards against a rewind: the run re-reads from
     * {@code watermark - overlap}, and an empty or all-unchanged chunk must never write that
     * earlier value back.
     */
    public void advanceWatermark(LocalDateTime sourceTs, String status, int rowsMerged) {
        Timestamp ts = Timestamp.valueOf(sourceTs);
        jdbc.update("MERGE INTO ETL_WATERMARK t "
                + "USING (SELECT ? AS WATERMARK_KEY FROM DUAL) s "
                + "   ON (t.WATERMARK_KEY = s.WATERMARK_KEY) "
                + " WHEN MATCHED THEN UPDATE SET "
                + "      t.LAST_SOURCE_TS = GREATEST("
                + "          NVL(t.LAST_SOURCE_TS, CAST(? AS TIMESTAMP(0))),"
                + "          CAST(? AS TIMESTAMP(0))), "
                + "      t.LAST_RUN_AT = SYSTIMESTAMP, "
                + "      t.LAST_RUN_STATUS = ?, "
                + "      t.ROWS_MERGED = ? "
                + " WHEN NOT MATCHED THEN INSERT "
                + "      (WATERMARK_KEY, LAST_SOURCE_TS, LAST_RUN_AT, LAST_RUN_STATUS, ROWS_MERGED) "
                + "      VALUES (s.WATERMARK_KEY, CAST(? AS TIMESTAMP(0)), SYSTIMESTAMP, ?, ?)",
                WATERMARK_KEY, ts, ts, status, rowsMerged, ts, status, rowsMerged);
    }

    // ----------------------------------------------------------------------- MERGE

    /**
     * The generated MERGE. Public so {@code MartSchemaIT} can assert that every column
     * {@code USER_TAB_COLUMNS} reports for {@code INCIDENT_FACT} appears in it — the assertion
     * that keeps this statement, {@code V3__mart.sql} and the report DTO from drifting apart.
     */
    public String mergeSql() {
        return mergeStatement().sql();
    }

    /** The fact's columns, in catalog order. Every merged row must supply a value for each. */
    public List<String> factColumns() {
        return mergeStatement().columns().stream().map(FactColumn::name).toList();
    }

    /**
     * MERGEs one chunk and returns how many rows ACTUALLY changed.
     *
     * <p>One {@code executeUpdate()} per row rather than a batch: Oracle's batch update counts
     * are allowed to come back as {@code SUCCESS_NO_INFO}, and {@code changed} is an assertion
     * target ({@code secondRunWithNoSourceChangeChangesNothing}), not a log line.
     */
    public int merge(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        MergeStatement statement = mergeStatement();
        List<FactColumn> columns = statement.columns();
        // execute(ConnectionCallback), not dataSource.getConnection(): the chunk runs inside a
        // TransactionTemplate, and a connection taken straight off the DataSource would sit
        // outside that transaction and commit independently of the watermark advance.
        Integer changed = jdbc.execute((ConnectionCallback<Integer>) connection -> {
            int merged = 0;
            try (PreparedStatement ps = connection.prepareStatement(statement.sql())) {
                for (Map<String, Object> row : rows) {
                    int index = 1;
                    for (FactColumn column : columns) {
                        bind(ps, index++, row.get(column.name()), column.jdbcType());
                    }
                    // The three content-predicate binds, in the order they appear in the WHERE.
                    bind(ps, index++, row.get("SOURCE_UPDATED_ON"), Types.TIMESTAMP);
                    bind(ps, index++, row.get("CV_DIGEST"), Types.NUMERIC);
                    bind(ps, index, row.get("CFG_GENERATION"), Types.NUMERIC);
                    merged += ps.executeUpdate();
                }
            }
            return merged;
        });
        return changed == null ? 0 : changed;
    }

    /**
     * Replaces an incident's quarantine rows.
     *
     * <p>DELETE first, unconditionally: a value corrected in Redmine must CLEAR the
     * quarantine, not sit next to a stale duplicate of itself.
     */
    public void saveRejects(long noIncident, List<Object[]> rejects) {
        jdbc.update("DELETE FROM INCIDENT_FACT_REJECT WHERE NO_INCIDENT = ?", noIncident);
        for (Object[] reject : rejects) {
            jdbc.update("INSERT INTO INCIDENT_FACT_REJECT "
                    + "(NO_INCIDENT, CUSTOM_FIELD_ID, LOGICAL_NAME, RAW_VALUE, REASON) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    noIncident, reject[0], reject[1], reject[2], reject[3]);
        }
    }

    // ------------------------------------------------------------- MERGE generation

    /** One catalog column, with the cast and JDBC type its bind needs. */
    private record FactColumn(String name, String castExpression, int jdbcType) {
    }

    private record MergeStatement(String sql, List<FactColumn> columns) {
    }

    private MergeStatement mergeStatement() {
        MergeStatement cached = merge;
        if (cached == null) {
            synchronized (this) {
                cached = merge;
                if (cached == null) {
                    cached = buildMerge();
                    merge = cached;
                }
            }
        }
        return cached;
    }

    private MergeStatement buildMerge() {
        List<FactColumn> columns = new ArrayList<>();
        jdbc.query("SELECT COLUMN_NAME, DATA_TYPE, CHAR_LENGTH FROM USER_TAB_COLUMNS "
                        + "WHERE TABLE_NAME = ? ORDER BY COLUMN_ID",
                ps -> ps.setString(1, FACT_TABLE),
                rs -> {
                    columns.add(factColumn(rs.getString(1).toUpperCase(Locale.ROOT),
                            rs.getString(2), rs.getInt(3)));
                });
        if (columns.isEmpty()) {
            throw new IllegalStateException(
                    FACT_TABLE + " has no columns in USER_TAB_COLUMNS -- has V3__mart.sql run?");
        }

        String using = columns.stream()
                .map(c -> c.castExpression() + " AS " + c.name())
                .collect(Collectors.joining(", "));
        String updates = columns.stream()
                .filter(c -> !KEY_COLUMN.equals(c.name()))   // Oracle forbids updating the ON key
                .map(c -> "t." + c.name() + " = s." + c.name())
                .collect(Collectors.joining(", "));
        String insertColumns = columns.stream().map(FactColumn::name)
                .collect(Collectors.joining(", "));
        String insertValues = columns.stream().map(c -> "s." + c.name())
                .collect(Collectors.joining(", "));

        String sql = "MERGE INTO " + FACT_TABLE + " t"
                + " USING (SELECT " + using + " FROM DUAL) s"
                + " ON (t." + KEY_COLUMN + " = s." + KEY_COLUMN + ")"
                + " WHEN MATCHED THEN UPDATE SET " + updates
                // DECODE, not <>: <> is not NULL-safe and every one of these three can be NULL.
                // All three terms are required -- see the class javadoc.
                + " WHERE DECODE(t.SOURCE_UPDATED_ON, ?, 0, 1) = 1"
                + "    OR DECODE(t.CV_DIGEST, ?, 0, 1) = 1"
                + "    OR DECODE(t.CFG_GENERATION, ?, 0, 1) = 1"
                + " WHEN NOT MATCHED THEN INSERT (" + insertColumns + ")"
                + " VALUES (" + insertValues + ")";
        log.debug("generated MERGE over {} catalog columns of {}", columns.size(), FACT_TABLE);
        return new MergeStatement(sql, List.copyOf(columns));
    }

    private static FactColumn factColumn(String name, String dataType, int charLength) {
        // The cast is what gives Oracle a type for the bind. Without it a SELECT ? FROM DUAL
        // is ORA-00932 on the first CLOB or TIMESTAMP column.
        if ("CLOB".equals(dataType)) {
            // CAST(? AS CLOB) is not legal; TO_CLOB is. Bound as a String, hence Types.VARCHAR.
            return new FactColumn(name, "TO_CLOB(?)", Types.VARCHAR);
        }
        if ("VARCHAR2".equals(dataType) || "CHAR".equals(dataType)) {
            return new FactColumn(name,
                    "CAST(? AS " + dataType + "(" + Math.max(charLength, 1) + " CHAR))",
                    Types.VARCHAR);
        }
        if (dataType.startsWith("NUMBER") || "FLOAT".equals(dataType)) {
            return new FactColumn(name, "CAST(? AS NUMBER)", Types.NUMERIC);
        }
        if ("DATE".equals(dataType)) {
            return new FactColumn(name, "CAST(? AS DATE)", Types.DATE);
        }
        if (dataType.startsWith("TIMESTAMP")) {
            // DATA_TYPE already reads "TIMESTAMP(0)" for the mart's timestamp columns.
            return new FactColumn(name, "CAST(? AS " + dataType + ")", Types.TIMESTAMP);
        }
        throw new IllegalStateException("no MERGE cast for " + FACT_TABLE + "." + name
                + " of type " + dataType + " -- add one when V3__mart.sql adds the column");
    }

    private static void bind(PreparedStatement ps, int index, Object value, int jdbcType)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, jdbcType);
            return;
        }
        if (value instanceof LocalDateTime ldt) {
            ps.setTimestamp(index, Timestamp.valueOf(ldt));
        } else if (value instanceof LocalDate date) {
            ps.setDate(index, java.sql.Date.valueOf(date));
        } else {
            ps.setObject(index, value);
        }
    }

    // -------------------------------------------------------------------- plumbing

    private static void bindIds(PreparedStatement ps, List<Long> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            ps.setLong(i + 1, ids.get(i));
        }
    }

    /** Expands the {@code IN (%s)} placeholder to one bind per id. */
    private static String inList(String sql, int count) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(count, "?"));
        return sql.replace("%s", placeholders);
    }

    /**
     * One named block of {@code incident-delta.sql}, with {@code ${mirror}} and
     * {@code ${scope}} already expanded.
     *
     * <p>Public so Task 12's reconcile reads its mirror-side SQL out of the SAME file and the
     * SAME scope predicate this ETL imports through. A reconcile with its own copy of the
     * tracker filter and the rolling window compares an unscoped mirror population against a
     * scoped fact table and reports drift on every month, forever.
     */
    public String section(String name) {
        String sql = sections.get(name);
        if (sql == null) {
            throw new IllegalStateException(
                    DELTA_SQL_RESOURCE + " has no section --@ " + name);
        }
        return sql;
    }

    /** Splits {@code incident-delta.sql} on its {@code --@ <name>} markers. */
    private static Map<String, String> loadSections(String mirrorSchema) {
        String text;
        try (InputStream in = new ClassPathResource(DELTA_SQL_RESOURCE).getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        text = text.replace("${mirror}", mirrorSchema);

        Map<String, String> sections = new LinkedHashMap<>();
        String name = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("--@")) {
                if (name != null) {
                    sections.put(name, body.toString());
                }
                name = line.substring(3).trim();
                body.setLength(0);
            } else if (name != null) {
                body.append(line).append('\n');
            }
        }
        if (name != null) {
            sections.put(name, body.toString());
        }
        return Map.copyOf(expandScope(sections));
    }

    /**
     * Substitutes the {@code scope} section into every other section's {@code ${scope}}.
     *
     * <p>Done AFTER the split, and non-recursively, so the scope predicate exists exactly once
     * in the codebase and the delta, the reconcile, the prune and the mirror-lag probe cannot
     * drift apart. Its own comment lines are stripped: the placeholder sits mid-statement
     * (right after {@code WHERE}), where a leading {@code --} would comment out the rest of
     * that physical line.
     */
    private static Map<String, String> expandScope(Map<String, String> sections) {
        String scope = sections.get("scope");
        if (scope == null) {
            throw new IllegalStateException(DELTA_SQL_RESOURCE + " has no section --@ scope");
        }
        String predicate = scope.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .filter(line -> !line.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        Map<String, String> expanded = new LinkedHashMap<>();
        sections.forEach((name, sql) ->
                expanded.put(name, "scope".equals(name) ? predicate
                        : sql.replace("${scope}", predicate)));
        return expanded;
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
