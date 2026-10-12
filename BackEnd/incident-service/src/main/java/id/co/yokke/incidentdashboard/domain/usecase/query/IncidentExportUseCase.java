package id.co.yokke.incidentdashboard.domain.usecase.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.IncidentRowDto;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/incidents/export} — the one bulk egress path in the system (Task 16).
 *
 * <h2>Server-side, and it has to be</h2>
 *
 * <p>The export RE-QUERIES the full filtered set here. It is not a transform of what
 * {@code /api/incidents} returned: if it were, the {@code export} privilege would be
 * unenforceable by construction, because a {@code viewer} holding only
 * {@code incident.ops:view} could page the query endpoint to completion and rebuild the
 * identical workbook. The view/export split is the only privilege boundary in this system.
 *
 * <h2>The cap and the audit row are ONE operation</h2>
 *
 * <p>{@link #export} is the only way to obtain export rows — {@code IncidentQueryUseCase.rows}
 * is package-private and this class is its only caller — and it applies the cap and writes the
 * {@code EXPORT_AUDIT} row before it returns anything. There is therefore no code path that
 * yields bulk rows without recording who pulled them, under what filter, how many, and whether
 * the cap bit. Both halves are load-bearing:
 *
 * <ul>
 *   <li>Without the cap this is the one unbounded read in the service — the {@code size ≤ 200}
 *       bound on {@code /api/incidents} deliberately does not apply here.
 *   <li>Without the audit row a bulk pull of production customer and staff names is the
 *       incident nobody can reconstruct afterwards.
 * </ul>
 *
 * <p>{@code @Transactional} so a failure between the read and the audit insert cannot leave the
 * rows served and the trail empty.
 *
 * <p>The rows themselves are NEVER logged. The audit table is the record; the log gets a count.
 */
@Service
@Lazy
public class IncidentExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncidentExportUseCase.class);

    private final IncidentQueryUseCase query;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int maxRows;

    public IncidentExportUseCase(IncidentQueryUseCase query, JdbcTemplate jdbc,
            ObjectMapper objectMapper, @Value("${incident.export.max-rows:50000}") int maxRows) {
        this.query = query;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.maxRows = maxRows;
    }

    /**
     * The capped, audited result of one export.
     *
     * @param truncated the cap bit — the UI raises a visible banner, and a capped export is a
     *     different event from a complete one in the audit trail
     */
    public record ExportResult(String exportId, int rowCount, boolean truncated, int maxRows,
            List<IncidentRowDto> content) {
    }

    @Transactional
    public ExportResult export(String username, IncidentFilter filter) {
        // One row past the cap: that extra row is how truncation is DETECTED rather than
        // guessed from rowCount == maxRows, which is also true for an exactly-full export.
        List<IncidentRowDto> rows = query.rows(filter, maxRows + 1);
        boolean truncated = rows.size() > maxRows;
        List<IncidentRowDto> content = truncated ? List.copyOf(rows.subList(0, maxRows))
                : List.copyOf(rows);

        String exportId = UUID.randomUUID().toString();
        audit(exportId, username, filter, content.size(), truncated);

        log.info("export {} by '{}': {} rows, truncated={}", exportId, username, content.size(),
                truncated);
        return new ExportResult(exportId, content.size(), truncated, maxRows, content);
    }

    /**
     * {@code TO_CLOB(?)} for {@code FILTER_JSON}: it is a CLOB precisely so the WHOLE filter
     * set survives, and the wide queries worth auditing are the ones a VARCHAR2 would truncate.
     * {@code TRUNCATED} is the {@code CHAR(1)} {@code 'Y'}/{@code 'N'} flag V4 constrains it to,
     * not a boolean.
     */
    private void audit(String exportId, String username, IncidentFilter filter, int rowCount,
            boolean truncated) {
        jdbc.update("INSERT INTO EXPORT_AUDIT "
                        + "(EXPORT_ID, USERNAME, REQUESTED_AT, FILTER_JSON, ROW_COUNT, TRUNCATED) "
                        + "VALUES (?, ?, SYSDATE, TO_CLOB(?), ?, ?)",
                exportId, username, filterJson(filter), rowCount, truncated ? "Y" : "N");
    }

    /**
     * The filter, verbatim. A failure to render it must not fail the export AND must not leave
     * the row unwritten — an audit row with an unreadable filter still records who pulled how
     * much and when.
     */
    private String filterJson(IncidentFilter filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            log.warn("could not serialise the export filter; auditing the row without it", e);
            return "{\"error\":\"filter could not be serialised\"}";
        }
    }
}
