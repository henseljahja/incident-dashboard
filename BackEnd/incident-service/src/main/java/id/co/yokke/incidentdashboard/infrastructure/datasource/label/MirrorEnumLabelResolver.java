package id.co.yokke.incidentdashboard.infrastructure.datasource.label;

import id.co.yokke.incidentdashboard.domain.usecase.label.EnumLabelResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Alternative {@link EnumLabelResolver}: reads {@code <mirror>CUSTOM_FIELD_ENUMERATIONS} —
 * the real Redmine table — instead of the synthetic {@code REF_CUSTOM_FIELD_ENUM} stand-in.
 *
 * <p>{@code custom_field_enumerations} is one of the four tables the sync team does not
 * deliver, so this path is expected to be unused in v1. It exists so that flipping
 * {@code incident.label.enum-resolver} to {@code mirror} the day it IS delivered is a config
 * change, not code. Selecting it against a mirror that still lacks the table must fail loudly
 * at startup — silently returning {@code "Unknown (...)"} for every row in the mart would be
 * far worse than a boot failure naming the missing table.
 */
@Component
@ConditionalOnProperty(name = "incident.label.enum-resolver", havingValue = "mirror")
public class MirrorEnumLabelResolver implements EnumLabelResolver {

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    private final AtomicReference<Map<Long, Map<Long, String>>> snapshot =
            new AtomicReference<>(Map.of());

    public MirrorEnumLabelResolver(
            JdbcTemplate jdbcTemplate, @Value("${incident.mirror.schema}") String mirrorSchema) {
        this.jdbcTemplate = jdbcTemplate;
        // mirrorSchema carries its own trailing separator (dot in prod, underscore in test) --
        // concatenate, never add one.
        this.table = mirrorSchema + "CUSTOM_FIELD_ENUMERATIONS";
        failFastIfTableMissing();
        refresh();
    }

    private void failFastIfTableMissing() {
        try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1 = 0",
                    Long.class);
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "incident.label.enum-resolver=mirror requires " + table
                            + ", which does not exist in the mirror. custom_field_enumerations "
                            + "is not delivered by the sync team -- switch the property back to "
                            + "'local' (the default) until it is.",
                    e);
        }
    }

    @Override
    public String resolve(long customFieldId, Long enumValueId) {
        if (enumValueId == null) {
            return UNCLASSIFIED;
        }
        Map<Long, String> byEnumValue = snapshot.get().get(customFieldId);
        String label = byEnumValue == null ? null : byEnumValue.get(enumValueId);
        return label != null ? label : "Unknown (" + enumValueId + ")";
    }

    @Override
    public void refresh() {
        Map<Long, Map<Long, String>> loaded = new HashMap<>();
        jdbcTemplate.query(
                "SELECT CUSTOM_FIELD_ID, ID AS ENUM_VALUE_ID, NAME AS LABEL FROM " + table,
                rs -> {
                    long customFieldId = rs.getLong("CUSTOM_FIELD_ID");
                    long enumValueId = rs.getLong("ENUM_VALUE_ID");
                    loaded.computeIfAbsent(customFieldId, id -> new HashMap<>())
                            .put(enumValueId, rs.getString("LABEL"));
                });
        snapshot.set(Map.copyOf(loaded));
    }
}
