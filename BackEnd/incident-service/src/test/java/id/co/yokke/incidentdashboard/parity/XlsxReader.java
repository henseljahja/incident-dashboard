package id.co.yokke.incidentdashboard.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads the delivered stakeholder workbook.
 *
 * <p><b>THE WORKBOOK IS PRODUCTION DATA.</b> It carries real customer names, staff names and
 * incident descriptions, it is git-ignored, and it must stay that way. Nothing in this class
 * writes a cell value to a log, an exception message or a file: every method that could leak
 * one returns it to the caller only, and {@link ParityDiff} — the only consumer — keys its
 * findings on column name and row index rather than on content.
 *
 * <p>The workbook is optional. A fresh clone has no production data, so {@link #locate()}
 * returns {@link Optional#empty()} there and the parity tests SKIP rather than fail.
 */
public final class XlsxReader implements AutoCloseable {

    /** The delivered report, as named by the stakeholders. */
    public static final String WORKBOOK_NAME = "Detail Incident, Problem, SR 2025 - 2026.xlsx";

    /**
     * The single sheet. It is a Power Query pull of the source SQL, so its header row IS the
     * column contract the mart has to reproduce — not a hand-made summary tab.
     */
    public static final String SHEET_NAME = "Query1";

    /** How far up from the module directory to look for {@code docs/reference/}. */
    private static final int MAX_ASCENT = 6;

    private final Workbook workbook;

    private final Sheet sheet;

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    private XlsxReader(Workbook workbook) {
        this.workbook = workbook;
        this.sheet = workbook.getSheet(SHEET_NAME);
        if (this.sheet == null) {
            throw new IllegalStateException(
                    "the delivered workbook has no '" + SHEET_NAME + "' sheet; that sheet is "
                            + "the Power Query pull of the source SQL and therefore the column "
                            + "contract under test");
        }
    }

    /**
     * The workbook path, if production data is present in this checkout.
     *
     * <p>Walks up from the working directory (the module dir under surefire/failsafe) rather
     * than hard-coding {@code ../../}, so the tests behave the same when run from the repo
     * root or from an IDE.
     */
    public static Optional<Path> locate() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= MAX_ASCENT && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("reference").resolve(WORKBOOK_NAME);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** The message a parity test skips with when this checkout has no production data. */
    public static String absenceMessage() {
        return "SKIPPED: docs/reference/" + WORKBOOK_NAME + " is not in this checkout. It is "
                + "production data and git-ignored, so a fresh clone cannot run Excel parity. "
                + "Copy the delivered workbook into docs/reference/ to enable it.";
    }

    /** Opens the located workbook. Call only when {@link #locate()} is present. */
    public static XlsxReader open(Path path) {
        try {
            return new XlsxReader(WorkbookFactory.create(path.toFile(), null, true));
        } catch (IOException e) {
            throw new UncheckedIOException("could not open the delivered workbook", e);
        }
    }

    /**
     * Header names exactly as the workbook spells them, left to right.
     *
     * <p>Column NAMES are structure, not data — they are safe to report. Cell VALUES are not.
     */
    public List<String> headers() {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            throw new IllegalStateException(SHEET_NAME + " has no header row");
        }
        List<String> names = new ArrayList<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String name = text(header.getCell(c));
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Every data row, keyed by the raw header name.
     *
     * <p>Values are the DISPLAYED strings, which is what parity is against: the workbook is
     * what the stakeholder reads, and its rendering of a date or a number is part of the
     * contract. A blank cell becomes {@code ""}.
     */
    public List<Map<String, String>> rows() {
        List<String> headers = headers();
        List<Map<String, String>> rows = new ArrayList<>();
        int first = sheet.getFirstRowNum() + 1;
        for (int r = first; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            boolean anyValue = false;
            for (int c = 0; c < headers.size(); c++) {
                String value = text(row.getCell(c));
                anyValue |= !value.isEmpty();
                values.put(headers.get(c), value);
            }
            if (anyValue) {
                rows.add(values);
            }
        }
        return rows;
    }

    /**
     * Name normalisation for column matching: uppercase, and every non-alphanumeric character
     * dropped. {@code No_incident}, {@code no incident} and {@code NOINCIDENT} all collapse to
     * the same key, so the workbook's inconsistent casing and underscoring cannot by itself
     * make a column look missing.
     */
    public static String normalise(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toUpperCase(ch));
            }
        }
        return out.toString();
    }

    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
