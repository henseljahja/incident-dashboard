package id.co.yokke.incidentdashboard.infrastructure.rest.dto.res;

import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row of {@code V_INCIDENT}, on the wire.
 *
 * <h2>The field list is NEVER hand-typed</h2>
 *
 * <p>This DTO carries a column-keyed map rather than declared components, and the keys come
 * from {@code USER_TAB_COLUMNS} — the same catalog-driven approach
 * {@code MartRefreshRepository.buildMerge()} uses for the MERGE. {@code V3__mart.sql} is the
 * only place the mart's columns are enumerated, and a hand-typed DTO is exactly how a view
 * column silently stops reaching the delivered workbook: the view keeps it, the export quietly
 * drops it, and nobody notices until a stakeholder diffs two spreadsheets.
 *
 * <p>{@code IncidentQueryIT.dtoFieldsAreDrivenByTheViewCatalog} asserts the correspondence in
 * both directions and holds the tripwire list, so adding a column to {@code V_INCIDENT}
 * without a deliberate decision fails the build.
 *
 * <p>The one hand-maintained thing here is {@link #INTERNAL_COLUMNS}: an <em>exclusion</em>
 * list, not a field list. The ETL's bookkeeping is not report data and has no business leaving
 * the service.
 */
public record IncidentRowDto(Map<String, Object> values) {

    /**
     * View columns that exist for the ETL and never reach a client: the merge watermark, the
     * custom-value digest and the config generation a row was derived under.
     *
     * <p>Plus two V9 echoes that are debugging aids, not report data: {@code REDMINE_IS_CLOSED}
     * (Redmine's raw flag, before {@code CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE}) and
     * {@code SLA_AT_RISK_DAYS} (the config cushion copied onto the fact). {@code AGING_INCIDENT}
     * is dropped from the API row and the export (stakeholders' updated datamart query dropped it,
     * Phase 2 note 16); {@code V_INCIDENT} itself is not rewritten. The derived V9
     * columns {@code STOP_AT}, {@code CLOSE_BASIS} and {@code SLA_AT_RISK} ARE exported.
     */
    public static final Set<String> INTERNAL_COLUMNS = Set.of(
            "ETL_UPDATED_AT", "SOURCE_UPDATED_ON", "CV_DIGEST", "CFG_GENERATION",
            "REDMINE_IS_CLOSED", "SLA_AT_RISK_DAYS", "AGING_INCIDENT");

    public IncidentRowDto {
        // LinkedHashMap, not Map.copyOf: the catalog order is the report's column order and
        // Task 18 derives the workbook header from it.
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Serialises as a flat JSON object keyed by view column, not as {@code {"values":…}}. */
    @JsonValue
    @Override
    public Map<String, Object> values() {
        return values;
    }

    /**
     * Reads exactly the given columns off the current row.
     *
     * <p>CLOBs are materialised here: a {@code java.sql.Clob} handed to Jackson serialises as
     * a locator's bean properties, and the locator is dead by the time the response is
     * written.
     */
    public static IncidentRowDto from(ResultSet rs, List<String> columns) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column : columns) {
            values.put(column, normalise(rs, column));
        }
        return new IncidentRowDto(values);
    }

    /**
     * Converts one column to something Jackson can actually write.
     *
     * <p>The fallback at the end is not defensive padding. {@code ojdbc} returns an opaque
     * {@code oracle.sql.TIMESTAMP} from {@code getObject} for a {@code TIMESTAMP} column, and
     * Jackson serialises that as {@code {"bytes":…,"stream":…}} before failing partway through
     * the response — a 500 whose body already contains half a page of customer data. Asking
     * the driver for a JDBC type instead is the fix.
     */
    private static Object normalise(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            // A zero-length locator is NOT NULL on Oracle but carries no text -- see
            // artifacts/oracle-empty-clob-finding.md. It renders as "", never as null.
            return length == 0 ? ""
                    : clob.getSubString(1, (int) Math.min(length, Integer.MAX_VALUE));
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof BigDecimal || value instanceof Number || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        try {
            Timestamp timestamp = rs.getTimestamp(column);
            if (timestamp != null) {
                return timestamp.toLocalDateTime();
            }
        } catch (SQLException notATimestamp) {
            // Fall through: whatever it is, its string rendering is what the report carries.
        }
        return rs.getString(column);
    }
}
