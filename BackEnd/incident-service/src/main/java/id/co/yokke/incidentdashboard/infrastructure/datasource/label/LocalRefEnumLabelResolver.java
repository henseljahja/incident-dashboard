package id.co.yokke.incidentdashboard.infrastructure.datasource.label;

import id.co.yokke.incidentdashboard.domain.usecase.label.EnumLabelResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Default {@link EnumLabelResolver}: reads {@code REF_CUSTOM_FIELD_ENUM}, the synthetic
 * stand-in for {@code redmine.custom_field_enumerations} that the sync team does not deliver
 * (see {@code V1__config_tables.sql}).
 *
 * <p>{@code @Lazy}: with no {@code incident.label.enum-resolver} property set this is the
 * ACTIVE resolver ({@code matchIfMissing = true}), so it would otherwise be constructed eagerly
 * in every Spring context — including the datasource-less scaffold smoke test, which has no
 * {@link JdbcTemplate} bean to inject. Laziness defers that wiring to first real use.
 */
@Component
@Lazy
@ConditionalOnProperty(
        name = "incident.label.enum-resolver",
        havingValue = "local",
        matchIfMissing = true)
public class LocalRefEnumLabelResolver implements EnumLabelResolver {

    private final JdbcTemplate jdbcTemplate;

    /** customFieldId -> (enumValueId -> label). Loaded once, replaced wholesale on refresh. */
    private final AtomicReference<Map<Long, Map<Long, String>>> snapshot =
            new AtomicReference<>(Map.of());

    public LocalRefEnumLabelResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        refresh();
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
                "SELECT CUSTOM_FIELD_ID, ENUM_VALUE_ID, LABEL FROM REF_CUSTOM_FIELD_ENUM",
                rs -> {
                    long customFieldId = rs.getLong("CUSTOM_FIELD_ID");
                    long enumValueId = rs.getLong("ENUM_VALUE_ID");
                    loaded.computeIfAbsent(customFieldId, id -> new HashMap<>())
                            .put(enumValueId, rs.getString("LABEL"));
                });
        snapshot.set(Map.copyOf(loaded));
    }
}
