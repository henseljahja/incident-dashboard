package id.co.yokke.incidentdashboard.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import id.co.yokke.incidentdashboard.domain.model.SolutionType;
import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 17 — does the mart reproduce the report that is already being delivered?
 *
 * <p>Three levels, and only the first two can run in CI:
 *
 * <table>
 *   <tr><th>Level</th><th>Proves</th><th>Runs</th></tr>
 *   <tr><td>1 — column parity</td>
 *       <td>every header in {@code Query1} resolves to a mart field</td><td>always</td></tr>
 *   <tr><td>2 — logic parity on the fixture</td>
 *       <td>the derived classifiers are right</td><td>always</td></tr>
 *   <tr><td>3 — full row-level diff</td>
 *       <td>the mart reproduces the workbook row for row</td>
 *       <td>only with {@code PARITY_FULL=true}</td></tr>
 * </table>
 *
 * <p>Level 3 is <b>gated, not silently absent</b>: it needs a mirror loaded from real
 * UAT/production data, and the CI fixture cannot contain the workbook's 2025–2026 rows. See
 * {@code docs/reference/PARITY.md} for the exact manual command.
 *
 * <p><b>The workbook is production data.</b> It is git-ignored and carries real customer and
 * staff names, so no assertion message in this class may echo a cell. Failures name a column
 * and a row INDEX; {@link ParityDiff} is built that way on purpose. When the workbook is
 * absent — a fresh clone — the workbook-dependent tests SKIP, so the build still goes green.
 */
class ExcelParityIT extends AbstractOracleIT {

    /** Structure, not data: the delivered sheet's shape, recorded in {@code PARITY.md}. */
    private static final int DELIVERED_COLUMN_COUNT = 35;

    /**
     * Issues from the hand-authored {@code fixtures/custom_values.csv}, chosen there precisely
     * so each SOLUTION branch comes out unambiguous. See {@code fixtures/README.md}.
     */
    private static final long CLOSE_EQUALS_RESOLUTION = 22868L;

    /** {@code close_date = ''} (a row that exists but is empty) with a resolution date. */
    private static final long BLANK_CLOSE_WITH_RESOLUTION = 22878L;

    /** {@code close_date} strictly after {@code resolution_date}. */
    private static final long CLOSE_AFTER_RESOLUTION = 22882L;

    /**
     * Columns Level 3 does NOT compare, and why. Every carve-out is a place the two designs
     * legitimately differ; anything not listed here is compared.
     *
     * <ul>
     *   <li>{@code AGING_INCIDENT} / {@code AGING_LAST_STATUS} are days-since-NOW. They were
     *       correct on the day the workbook was pulled and are correct again at query time —
     *       against different clocks. Comparing them diffs the two run dates, not the mart.
     *   <li>{@code AGING_NEW} is days-to-close for a closed row but days-to-NOW for an open
     *       one, so it carries the same clock for the open population. Its closed-row
     *       arithmetic is asserted at Level 2 instead, where the dates are pinned.
     * </ul>
     */
    private static final Set<String> CLOCK_DERIVED =
            Set.of("AGING_INCIDENT", "AGING_LAST_STATUS", "AGING_NEW");

    private static final String KEY_HEADER = "No_incident";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------ Level 1: columns

    /**
     * {@code Query1} is a Power Query pull of the source SQL, so its header row IS the column
     * contract. A header that resolves to nothing in {@code V_INCIDENT} is a column the
     * stakeholder has today and would lose on cutover.
     *
     * <p>Matching is on normalised names ({@link XlsxReader#normalise}) plus one alias rule:
     * an {@code RPT_X} view column is the RENDERED form of report column {@code X} (see the
     * {@code RPT_} block in {@code V3__mart.sql}), so it answers to {@code X}.
     */
    @Test
    @DisplayName("Level 1 — every column the delivered report has exists in the mart")
    void martExposesEveryColumnTheDeliveredReportHas() {
        Optional<Path> located = XlsxReader.locate();
        assumeTrue(located.isPresent(), XlsxReader.absenceMessage());

        List<String> headers;
        try (XlsxReader reader = XlsxReader.open(located.get())) {
            headers = reader.headers();
        }

        assertThat(headers)
                .as("the delivered sheet's shape; a column added or dropped upstream changes "
                        + "what we owe and must be noticed here")
                .hasSize(DELIVERED_COLUMN_COUNT);

        Map<String, String> martByReportName = martColumnsByReportName();

        List<String> missingColumns = headers.stream()
                .filter(header -> !martByReportName.containsKey(XlsxReader.normalise(header)))
                .toList();

        assertThat(missingColumns)
                .as("column NAMES are structure and safe to report; each one here is a field "
                        + "the delivered report carries and the mart cannot produce")
                .isEmpty();

        // MTTD is not in the source report and is not derivable from Redmine's data. It must
        // not appear anywhere -- neither invented by us nor smuggled in from upstream.
        assertThat(headers).noneMatch(header -> XlsxReader.normalise(header).contains("MTTD"));
        assertThat(martByReportName.values()).noneMatch(column -> column.contains("MTTD"));
    }

    // ------------------------------------------------------------------- Level 2: logic

    /**
     * The fixture rows are not the workbook's rows, so this cannot prove row parity — but the
     * classifiers are pure functions of the source semantics, so a wrong one shows up here.
     *
     * <p>Every expected value is DERIVED: {@code SLA} from {@code CFG_SLA_POLICY} keyed on the
     * row's own {@code PRIORITY_ID}, and the SOLUTION outcomes from the close/resolution dates
     * the hand-authored fixture pins. Typing a constant here would invite an implementer to
     * break {@code DerivedFields} to make it green.
     */
    @Test
    @DisplayName("Level 2 — the mart reproduces the report's derived values on known rows")
    void reproducesTheReportsDerivedValuesOnKnownRows() {
        loadFixtures();
        assertThat(useCase(MartRefreshUseCase.class).refresh().changed())
                .as("the production-derived fixture CSVs must be present -- run "
                        + "python3 scripts/extract-fixtures.py (see fixtures/README.md)")
                .isPositive();

        Map<String, Object> permanent = viewRow(CLOSE_EQUALS_RESOLUTION);
        Map<String, Object> workaround = viewRow(BLANK_CLOSE_WITH_RESOLUTION);
        Map<String, Object> both = viewRow(CLOSE_AFTER_RESOLUTION);

        // --- SOLUTION. Branch order and the unparenthesised AND/OR asymmetry are the spec;
        // see artifacts/solution-precedence-finding.md.
        assertThat(permanent.get("SOLUTION"))
                .as("close_date = resolution_date is branch 3's third disjunct")
                .isEqualTo(SolutionType.PERMANENT);
        assertThat(workaround.get("SOLUTION"))
                .as("an EMPTY close_date row with a resolution date is branch 2 -- and it is "
                        + "NOT the mirror image of the permanent case")
                .isEqualTo(SolutionType.WORKAROUND);
        assertThat(both.get("SOLUTION"))
                .as("close_date after resolution_date is branch 4")
                .isEqualTo(SolutionType.WORKAROUND_AND_PERMANENT);

        // The misspelling is load-bearing: it is in the source query and therefore in the
        // delivered workbook. "Correcting" it is a valueMismatch on every such row.
        assertThat((String) both.get("SOLUTION"))
                .as("DO NOT FIX: the delivered report says 'Pemanent'")
                .contains("Pemanent")
                .doesNotContain("Permanent");

        // --- SLA. Keyed on PRIORITY_ID, never on the priority NAME: the ids are not
        // contiguous and not severity-ordered, and a rename must not move the budget.
        for (Map<String, Object> row : List.of(permanent, workaround, both)) {
            long priorityId = number(row.get("PRIORITY_ID")).longValue();
            Integer expected = jdbcTemplate.queryForObject(
                    "SELECT SLA_DAYS FROM CFG_SLA_POLICY WHERE PRIORITY_ID = ?",
                    Integer.class, priorityId);
            assertThat(expected)
                    .as("the SLA ladder must cover priority id " + priorityId)
                    .isNotNull();
            assertThat(number(row.get("SLA")).intValue())
                    .as("SLA for priority id " + priorityId + " comes from CFG_SLA_POLICY")
                    .isEqualTo(expected);
        }

        // --- AGING_NEW: whole days from creation to close.
        assertThat(number(permanent.get("AGING_NEW")).intValue())
                .isEqualTo(daysToClose(permanent));
        assertThat(number(both.get("AGING_NEW")).intValue()).isEqualTo(daysToClose(both));

        // V9 close clock (plan TM005, recorded requirement change). This fixture is CLOSED in
        // Redmine with an EMPTY close_date row. Before V9 the empty row won and AGING_NEW was
        // NULL / 'Unknown', exactly as the source's timestampdiff(DAY, d, '') returns. V9
        // decides "closed beats an empty close-date row": the clock stops at CLOSED_ON. The
        // empty-row -> NULL rule still holds for OPEN tickets (CloseClockIT).
        assertThat(number(workaround.get("HAS_CLOSE_DATE_ROW")).intValue())
                .as("fixture shape: the close_date row exists")
                .isEqualTo(1);
        assertThat(workaround.get("CLOSE_DATE")).as("fixture shape: ...and is empty").isNull();
        assertThat(number(workaround.get("REDMINE_IS_CLOSED")).intValue())
                .as("fixture shape: Redmine calls it closed")
                .isEqualTo(1);
        assertThat(workaround.get("CLOSE_BASIS"))
                .as("closed with an empty close-date row stops at Redmine's closed_on")
                .isEqualTo("CLOSED_ON");
        assertThat(workaround.get("STOP_AT"))
                .as("the stop date IS closed_on's day")
                .isNotNull()
                .isEqualTo(workaround.get("CLOSED_ON_DAY"));
        assertThat(number(workaround.get("AGING_NEW")).intValue())
                .as("days from creation to the stop date, no longer NULL")
                .isEqualTo(daysBetween(workaround, "STOP_AT"));
        assertThat(workaround.get("SLA_BUCKET"))
                .as("a measurable elapsed lands in a real band, and agrees with SLA_BREACHED")
                .isNotEqualTo("Unknown")
                .isEqualTo(band(number(workaround.get("AGING_NEW")).intValue(),
                        number(workaround.get("SLA")).intValue()));
        assertThat(number(workaround.get("SLA_BREACHED")).intValue() == 1)
                .isEqualTo("Breached".equals(workaround.get("SLA_BUCKET")));
        assertThat(number(workaround.get("SLA_AT_RISK")).intValue())
                .as("a closed ticket is never at risk")
                .isZero();
    }

    /**
     * Our sentinels and the workbook's blanks describe the SAME fact. The source emits SQL
     * NULL wherever a LEFT JOIN misses and the workbook renders that empty; this system
     * renders it as a countable label so it cannot vanish from an aggregate. Without this rule
     * the Level-3 gate is non-empty by construction on its first real run.
     */
    @Test
    @DisplayName("a sentinel against a blank workbook cell is a match, not a value mismatch")
    void sentinelLabelsAreNotCountedAsValueMismatchesAgainstBlanks() {
        List<Map<String, String>> workbook = List.of(
                new LinkedHashMap<>(Map.of(KEY_HEADER, "22868",
                        "priority", "", "status", "", "CATEGORY_INCIDENT", "")));
        Map<String, Map<String, String>> mart = Map.of("22868",
                new LinkedHashMap<>(Map.of(KEY_HEADER, "22868",
                        "priority", "Unclassified",
                        "status", "Unknown (999999)",
                        "CATEGORY_INCIDENT", "Unmapped")));

        ParityDiff clean = ParityDiff.compare(workbook, mart, KEY_HEADER,
                Set.of("priority", "status", "CATEGORY_INCIDENT"));
        assertThat(clean.valueMismatches())
                .as("every sentinel we render stands in for an absence the workbook renders "
                        + "as a blank -- " + clean.summary())
                .isEmpty();
        assertThat(clean.isClean()).isTrue();

        // The carve-out is one-directional. A sentinel where the workbook HAS a label means we
        // failed to resolve something the source could, which is a real gap and must not hide.
        Map<String, Map<String, String>> unresolved = Map.of("22868",
                new LinkedHashMap<>(Map.of(KEY_HEADER, "22868", "priority", "Unclassified")));
        List<Map<String, String>> labelled = List.of(
                new LinkedHashMap<>(Map.of(KEY_HEADER, "22868", "priority", "Low")));
        assertThat(ParityDiff.compare(labelled, unresolved, KEY_HEADER, Set.of("priority"))
                .valueMismatches())
                .as("a sentinel is only equivalent to a BLANK, never to a real label")
                .hasSize(1);

        // ...and the misspelling stays load-bearing: a corrected spelling is a mismatch.
        assertThat(ParityDiff.cellsAgree(SolutionType.WORKAROUND_AND_PERMANENT,
                "Workaround & Permanent Solution"))
                .as("DO NOT FIX the source's 'Pemanent'")
                .isFalse();
        assertThat(ParityDiff.cellsAgree(SolutionType.WORKAROUND_AND_PERMANENT,
                SolutionType.WORKAROUND_AND_PERMANENT)).isTrue();
    }

    // ------------------------------------------------------- Level 3: full row-level diff

    /**
     * The release gate. OPT-IN: it needs a mirror loaded from real UAT/production data, which
     * CI does not and must not have.
     *
     * <p>Named follow-up if it has never been run: "run Level-3 Excel parity against a
     * UAT-loaded mirror before the mart replaces the manual report". Trigger: cutover.
     */
    @Test
    @DisplayName("Level 3 — the mart reproduces every row of the delivered workbook (gated)")
    void reproducesEveryRowOfTheDeliveredWorkbook() {
        Optional<Path> located = XlsxReader.locate();
        assumeTrue(located.isPresent(), XlsxReader.absenceMessage());
        assumeTrue("true".equalsIgnoreCase(System.getenv("PARITY_FULL")),
                "SKIPPED: Level 3 needs a mirror loaded from real UAT/production data and is "
                        + "opt-in. See docs/reference/PARITY.md for the command. FOLLOW-UP: "
                        + "run it against a UAT-loaded mirror before the mart replaces the "
                        + "manually delivered report.");

        List<String> headers;
        List<Map<String, String>> workbookRows;
        try (XlsxReader reader = XlsxReader.open(located.get())) {
            headers = reader.headers();
            workbookRows = reader.rows();
        }

        Map<String, String> martByReportName = martColumnsByReportName();
        Map<String, Map<String, String>> martRows = readMart(headers, martByReportName);

        Set<String> compared = new LinkedHashSet<>(headers);
        compared.remove(KEY_HEADER);
        compared.removeIf(header -> CLOCK_DERIVED.contains(XlsxReader.normalise(header)));

        ParityDiff diff = ParityDiff.compare(workbookRows, martRows, KEY_HEADER, compared);

        assertThat(diff.missingFromMart())
                .as("rows the delivered report has and the mart does not -- a scope or ETL "
                        + "gap, NOT a classifier bug. " + diff.summary())
                .isEmpty();
        assertThat(diff.extraInMart())
                .as("rows the mart invented -- a window or scope filter that is too wide. "
                        + diff.summary())
                .isEmpty();
        assertThat(diff.valueMismatches())
                .as("cells that disagree, by column and 0-based data-row index; values are "
                        + "deliberately NOT echoed (production data). " + diff.summary())
                .isEmpty();
    }

    // ------------------------------------------------------------------------- helpers

    /**
     * {@code V_INCIDENT}'s columns keyed by the normalised REPORT name they answer to.
     *
     * <p>{@code RPT_*} columns are registered FIRST under their stripped name, so the
     * workbook's {@code DATE_CREATE} resolves to the rendered {@code RPT_DATE_CREATE} rather
     * than to the native timestamp it is derived from.
     */
    private Map<String, String> martColumnsByReportName() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'V_INCIDENT' "
                        + "ORDER BY COLUMN_ID", String.class);
        assertThat(columns).as("V_INCIDENT must exist before parity means anything")
                .isNotEmpty();

        Map<String, String> byReportName = new LinkedHashMap<>();
        for (String column : columns) {
            if (column.startsWith("RPT_")) {
                byReportName.put(XlsxReader.normalise(column.substring("RPT_".length())),
                        column);
            }
        }
        for (String column : columns) {
            byReportName.putIfAbsent(XlsxReader.normalise(column), column);
        }
        return byReportName;
    }

    /**
     * Every mart row, rendered as the strings the workbook holds and keyed by workbook header.
     *
     * <p>Dates and CLOBs are converted IN ORACLE: a Java-side {@code toString} on a
     * {@code Timestamp} yields a different rendering than the report's, which would score as a
     * value mismatch on every dated row.
     */
    private Map<String, Map<String, String>> readMart(List<String> headers,
            Map<String, String> martByReportName) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT COLUMN_NAME, DATA_TYPE FROM USER_TAB_COLUMNS "
                        + "WHERE TABLE_NAME = 'V_INCIDENT'")
                .forEach(row -> types.put(String.valueOf(row.get("COLUMN_NAME")),
                        String.valueOf(row.get("DATA_TYPE"))));

        List<String> selects = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String column = martByReportName.get(XlsxReader.normalise(headers.get(i)));
            assertThat(column).as("unresolved header at index " + i
                    + " -- Level 1 gates this and should have failed first").isNotNull();
            String alias = "C" + i;
            aliases.add(alias);
            selects.add(render(column, types.getOrDefault(column, "VARCHAR2")) + " AS " + alias);
        }

        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        int keyIndex = headers.indexOf(KEY_HEADER);
        for (Map<String, Object> raw : jdbcTemplate.queryForList(
                "SELECT " + String.join(", ", selects) + " FROM V_INCIDENT")) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                Object value = raw.get(aliases.get(i).toUpperCase(Locale.ROOT));
                row.put(headers.get(i), value == null ? "" : String.valueOf(value).trim());
            }
            rows.put(row.get(headers.get(keyIndex)), row);
        }
        return rows;
    }

    private static String render(String column, String dataType) {
        return switch (dataType) {
            case "DATE" -> "TO_CHAR(" + column + ", 'YYYY-MM-DD')";
            case "CLOB" -> "TO_CHAR(DBMS_LOB.SUBSTR(" + column + ", 2000, 1))";
            case "NUMBER", "FLOAT" -> "TO_CHAR(" + column + ")";
            default -> dataType.startsWith("TIMESTAMP")
                    ? "TO_CHAR(" + column + ", 'YYYY-MM-DD')"
                    : column;
        };
    }

    private Map<String, Object> viewRow(long noIncident) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT NO_INCIDENT, PRIORITY_ID, SLA, SOLUTION, AGING_NEW, SLA_BUCKET, "
                        + "RPT_DATE_CREATE, TO_CHAR(CLOSE_DATE, 'YYYY-MM-DD') AS CLOSE_DATE, "
                        // V9 close clock
                        + "HAS_CLOSE_DATE_ROW, REDMINE_IS_CLOSED, CLOSE_BASIS, SLA_BREACHED, "
                        + "SLA_AT_RISK, TO_CHAR(STOP_AT, 'YYYY-MM-DD') AS STOP_AT, "
                        + "TO_CHAR(CLOSED_ON, 'YYYY-MM-DD') AS CLOSED_ON_DAY "
                        + "FROM V_INCIDENT WHERE NO_INCIDENT = ?", noIncident);
        assertThat(rows).as("fixture issue " + noIncident + " must reach the mart").hasSize(1);
        Map<String, Object> row = new LinkedHashMap<>();
        rows.get(0).forEach((key, value) -> row.put(key.toUpperCase(Locale.ROOT), value));
        return row;
    }

    /** Whole calendar days from the row's own creation date to its own close date. */
    private static int daysToClose(Map<String, Object> row) {
        return daysBetween(row, "CLOSE_DATE");
    }

    /** The V3 SLA band for a measurable elapsed, from the row's own age and budget. */
    private static String band(int elapsedDays, int slaDays) {
        if (elapsedDays > slaDays) {
            return "Breached";
        }
        if (elapsedDays >= slaDays * 0.8) {
            return "80-100%";
        }
        return elapsedDays >= slaDays * 0.5 ? "50-80%" : "0-50%";
    }

    /** Whole calendar days from the row's own creation date to one of its own date columns. */
    private static int daysBetween(Map<String, Object> row, String endColumn) {
        LocalDate created = LocalDate.parse((String) row.get("RPT_DATE_CREATE"));
        LocalDate end = LocalDate.parse((String) row.get(endColumn));
        return (int) ChronoUnit.DAYS.between(created, end);
    }

    private static Number number(Object value) {
        assertThat(value).as("expected a number, got null").isNotNull();
        return (Number) value;
    }
}
