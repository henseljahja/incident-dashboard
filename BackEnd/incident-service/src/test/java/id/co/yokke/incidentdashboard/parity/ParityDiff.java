package id.co.yokke.incidentdashboard.parity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The comparison between the delivered workbook and {@code V_INCIDENT}. Pure: no I/O, no
 * Oracle, no POI — so the rules below can be asserted directly.
 *
 * <p><b>Three buckets, reported separately.</b> A row the mart never produced, a row the mart
 * invented, and a row whose value is wrong are three different bugs with three different
 * causes (scope filter · scope filter · classifier). Collapsing them into one "mismatch" count
 * makes the release gate unreadable.
 *
 * <p><b>Nothing here ever carries a cell value.</b> The workbook is production data — real
 * customer and staff names. A finding is identified by key, column name and row INDEX, all of
 * which are structure. That is also why {@link Mismatch} has no {@code expected}/{@code actual}
 * field: an AssertJ failure message prints the whole object.
 */
public record ParityDiff(List<String> missingFromMart,
                         List<String> extraInMart,
                         List<Mismatch> valueMismatches) {

    /**
     * One disagreeing cell. {@code workbookRowIndex} is 0-based over the sheet's DATA rows, so
     * a human can find it without the value being echoed here.
     */
    public record Mismatch(String key, String column, int workbookRowIndex) {
    }

    /**
     * {@code Unknown (&lt;id&gt;)} — an enum id, status or tracker with no reference row.
     * Deliberately loud rather than NULL.
     */
    private static final Pattern UNKNOWN_ID = Pattern.compile("Unknown \\(-?\\d+\\)");

    /**
     * The sentinels this system renders in place of the source's SQL NULL.
     *
     * <p>The source query emits NULL wherever a LEFT JOIN misses — an unset custom field, an
     * unresolvable enum id, an unmapped status — and the delivered workbook renders that as an
     * empty cell. This system renders the same absence as a countable label instead, because
     * unresolved values must never vanish from an aggregate. The two designs therefore
     * disagree on EVERY such row by construction, which would make the Level-3 gate non-empty
     * on its first real run for a reason that is not a defect.
     *
     * <p>So: a sentinel against a BLANK workbook cell is a MATCH. A sentinel against a
     * POPULATED workbook cell is still a mismatch — that one means the mart failed to resolve
     * a label the source could resolve, which is a real gap.
     */
    public static boolean isSentinel(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "Unclassified".equals(trimmed)
                || "Unmapped".equals(trimmed)
                || UNKNOWN_ID.matcher(trimmed).matches();
    }

    /** A cell is absent whether the source had NULL, {@code ''} or nothing at all. */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Whether one workbook cell and one mart cell agree.
     *
     * <p>Comparison is case- and content-SENSITIVE apart from surrounding whitespace. It has
     * to be: {@code 'Workaround & Pemanent Solution'} is misspelled in the source query and
     * therefore in the delivered workbook, and a lenient comparison would let a "corrected"
     * spelling pass parity.
     */
    public static boolean cellsAgree(String workbookValue, String martValue) {
        boolean workbookBlank = isBlank(workbookValue);
        if (workbookBlank && (isBlank(martValue) || isSentinel(martValue))) {
            return true;
        }
        if (workbookBlank || isBlank(martValue)) {
            return false;
        }
        return workbookValue.trim().equals(martValue.trim());
    }

    /**
     * Compares two keyed row sets over the given columns.
     *
     * @param workbookRows rows keyed by workbook header name, in sheet order
     * @param martRows rows keyed by the SAME header names (the caller maps mart columns onto
     *     the report aliases first), keyed by the same {@code keyColumn}
     * @param keyColumn the join key present in both — {@code No_incident}
     * @param comparedColumns which columns to compare; a column carved out here is one the two
     *     designs legitimately differ on, and every carve-out must be justified at the call site
     */
    public static ParityDiff compare(List<Map<String, String>> workbookRows,
            Map<String, Map<String, String>> martRows,
            String keyColumn,
            Set<String> comparedColumns) {

        Map<String, Integer> workbookIndex = new LinkedHashMap<>();
        List<String> missingFromMart = new ArrayList<>();
        List<Mismatch> mismatches = new ArrayList<>();

        for (int i = 0; i < workbookRows.size(); i++) {
            Map<String, String> row = workbookRows.get(i);
            String key = String.valueOf(row.get(keyColumn)).trim();
            workbookIndex.put(key, i);

            Map<String, String> mart = martRows.get(key);
            if (mart == null) {
                missingFromMart.add(key);
                continue;
            }
            for (String column : comparedColumns) {
                if (!cellsAgree(row.get(column), mart.get(column))) {
                    mismatches.add(new Mismatch(key, column, i));
                }
            }
        }

        Set<String> extra = new LinkedHashSet<>(martRows.keySet());
        extra.removeAll(workbookIndex.keySet());

        return new ParityDiff(List.copyOf(missingFromMart), List.copyOf(extra),
                List.copyOf(mismatches));
    }

    public boolean isClean() {
        return missingFromMart.isEmpty() && extraInMart.isEmpty() && valueMismatches.isEmpty();
    }

    /** Counts only — safe to print. Never add a value to this. */
    public String summary() {
        return "missingFromMart=" + missingFromMart.size()
                + " extraInMart=" + extraInMart.size()
                + " valueMismatches=" + valueMismatches.size();
    }
}
