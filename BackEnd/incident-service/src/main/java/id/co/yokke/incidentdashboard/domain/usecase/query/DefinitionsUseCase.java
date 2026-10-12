package id.co.yokke.incidentdashboard.domain.usecase.query;

import id.co.yokke.incidentdashboard.domain.usecase.etl.DerivedFields;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.DefinitionsResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.DefinitionsResponse.CloseRule;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The numbers behind the dashboard's ⓘ hints (plan 2026-09-15-stakeholder-notes-p1, T303).
 *
 * <p>Read straight from the config the ETL and the view actually apply — {@code CFG_SLA_POLICY},
 * {@code CFG_STATUS_STAGE_MAP.IS_CLOSED_OVERRIDE}, the {@link DerivedFields} unknown-priority
 * constants and the refresh cadence — so an explanation can never quote a threshold the numbers
 * on the same screen were not computed with. Config only; no incident row is read.
 */
@Service
@Lazy
public class DefinitionsUseCase {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final JdbcTemplate jdbc;
    private final long refreshIntervalMs;

    public DefinitionsUseCase(JdbcTemplate jdbc,
            @Value("${incident.etl.refresh-interval-ms:120000}") long refreshIntervalMs) {
        this.jdbc = jdbc;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public DefinitionsResponse definitions() {
        List<DefinitionsResponse.SlaPolicyItem> policy = jdbc.query(
                "SELECT PRIORITY_ID, PRIORITY_NAME, SLA_DAYS, AT_RISK_REMAINING_DAYS "
                        + "FROM CFG_SLA_POLICY ORDER BY SLA_DAYS ASC, PRIORITY_ID ASC",
                (rs, rowNum) -> new DefinitionsResponse.SlaPolicyItem(rs.getLong(1),
                        rs.getString(2), rs.getInt(3), rs.getInt(4)));

        List<DefinitionsResponse.ClosedStatus> closedByOverride = jdbc.query(
                "SELECT STATUS_ID, STATUS_NAME FROM CFG_STATUS_STAGE_MAP "
                        + "WHERE IS_CLOSED_OVERRIDE = 1 ORDER BY STATUS_ID",
                (rs, rowNum) -> new DefinitionsResponse.ClosedStatus(rs.getLong(1),
                        rs.getString(2)));

        return new DefinitionsResponse(policy,
                new DefinitionsResponse.UnknownPriority(DerivedFields.UNKNOWN_PRIORITY_SLA_DAYS,
                        DerivedFields.UNKNOWN_PRIORITY_AT_RISK_DAYS),
                closedByOverride,
                List.of(CloseRule.values()),
                refreshIntervalMinutes());
    }

    /** A whole number of minutes stays an integer on the wire; anything else keeps its fraction. */
    private Number refreshIntervalMinutes() {
        if (refreshIntervalMs % MILLIS_PER_MINUTE == 0) {
            return refreshIntervalMs / MILLIS_PER_MINUTE;
        }
        return (double) refreshIntervalMs / MILLIS_PER_MINUTE;
    }
}
