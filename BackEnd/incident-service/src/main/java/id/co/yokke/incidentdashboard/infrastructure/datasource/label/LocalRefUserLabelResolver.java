package id.co.yokke.incidentdashboard.infrastructure.datasource.label;

import id.co.yokke.incidentdashboard.domain.usecase.label.EnumLabelResolver;
import id.co.yokke.incidentdashboard.domain.usecase.label.UserLabelResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Reads {@code REF_USER}, the synthetic stand-in for {@code redmine.users} joined to
 * {@code redmine.email_addresses} (neither delivered by the sync team; see
 * {@code V1__config_tables.sql}).
 *
 * <p>Applies the THREE DIFFERENT rules the source report resolves user identity by — see
 * {@link UserLabelResolver} for why one {@code resolve(Long)} cannot express them.
 *
 * <p>{@code @Lazy} for the same reason as {@link LocalRefEnumLabelResolver}: this is the only
 * {@link UserLabelResolver} implementation, so it is always in the bean graph, including the
 * datasource-less scaffold smoke test that has no {@link JdbcTemplate} bean to inject.
 */
@Component
@Lazy
public class LocalRefUserLabelResolver implements UserLabelResolver {

    private record UserRow(String address, String firstname, String lastname) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final AtomicReference<Map<Long, UserRow>> snapshot = new AtomicReference<>(Map.of());

    public LocalRefUserLabelResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        refresh();
    }

    @Override
    public String authorLabel(Long userId) {
        // AUTHOR: address, NO fallback. NULL (both for an unknown user and for a known user
        // with no email row) is the deliberate parity exception to the never-null rule below.
        UserRow row = userId == null ? null : snapshot.get().get(userId);
        return row == null ? null : row.address();
    }

    @Override
    public String assigneeLabel(Long userId) {
        if (userId == null) {
            return EnumLabelResolver.UNCLASSIFIED;
        }
        UserRow row = snapshot.get().get(userId);
        if (row == null) {
            return "Unknown (" + userId + ")";
        }
        return row.address() != null ? row.address() : row.lastname();
    }

    @Override
    public String developerLabel(Long userId) {
        if (userId == null) {
            return EnumLabelResolver.UNCLASSIFIED;
        }
        UserRow row = snapshot.get().get(userId);
        if (row == null) {
            return "Unknown (" + userId + ")";
        }
        return row.firstname();
    }

    /** Reloads the cached snapshot; the ETL calls it once per run (Task 11). */
    @Override
    public void refresh() {
        Map<Long, UserRow> loaded = new HashMap<>();
        jdbcTemplate.query(
                "SELECT USER_ID, ADDRESS, FIRSTNAME, LASTNAME FROM REF_USER",
                (RowCallbackHandler) rs -> loaded.put(rs.getLong("USER_ID"),
                        new UserRow(rs.getString("ADDRESS"), rs.getString("FIRSTNAME"),
                                rs.getString("LASTNAME"))));
        snapshot.set(Map.copyOf(loaded));
    }
}
