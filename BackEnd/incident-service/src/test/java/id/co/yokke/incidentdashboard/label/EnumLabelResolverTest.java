package id.co.yokke.incidentdashboard.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.yokke.incidentdashboard.domain.usecase.label.EnumLabelResolver;
import id.co.yokke.incidentdashboard.domain.usecase.label.UserLabelResolver;
import id.co.yokke.incidentdashboard.infrastructure.datasource.label.LocalRefEnumLabelResolver;
import id.co.yokke.incidentdashboard.infrastructure.datasource.label.LocalRefUserLabelResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Plain unit test: {@link JdbcTemplate} is mocked, no database and no Spring context.
 *
 * <p>Covers both {@link EnumLabelResolver} (never-null, Unclassified vs. Unknown) and
 * {@link UserLabelResolver} (the three different user-identity rules), since both resolvers
 * share the same "load once, cache, resolve" shape and the same never-null discipline.
 */
class EnumLabelResolverTest {

    // ------------------------------------------------------------ enum resolver

    @Test
    @DisplayName("resolves a known enum value to its label")
    void resolvesAKnownEnumValueToItsLabel() {
        EnumLabelResolver resolver = enumResolver(
                new Object[] {41L, 27L, "Incident Type 01"},
                new Object[] {93L, 160L, "Impact - Low"});

        assertThat(resolver.resolve(41L, 27L)).isEqualTo("Incident Type 01");
        assertThat(resolver.resolve(93L, 160L)).isEqualTo("Impact - Low");
    }

    @Test
    @DisplayName("renders unknown ids visibly rather than dropping them")
    void rendersUnknownIdsVisiblyRatherThanDroppingThem() {
        EnumLabelResolver resolver = enumResolver(new Object[] {41L, 27L, "Incident Type 01"});

        assertThat(resolver.resolve(41L, 999999L)).isEqualTo("Unknown (999999)");
        // Also unresolvable when the custom field itself has no snapshot rows at all.
        assertThat(resolver.resolve(77L, 48L)).isEqualTo("Unknown (48)");
    }

    @Test
    @DisplayName("treats an absent value as Unclassified, not Unknown")
    void treatsAbsentValueAsUnclassifiedNotUnknown() {
        EnumLabelResolver resolver = enumResolver(new Object[] {41L, 27L, "Incident Type 01"});

        String label = resolver.resolve(41L, null);

        assertThat(label).isEqualTo("Unclassified");
        assertThat(label).isEqualTo(EnumLabelResolver.UNCLASSIFIED);
        assertThat(label).isNotEqualTo("Unknown (null)");
    }

    // ------------------------------------------------------------ user resolver

    @Test
    @DisplayName("authorLabel reads the email address, with no fallback")
    void authorLabelReadsAddressWithNoFallback() {
        UserLabelResolver resolver = userResolver(
                new Object[] {5L, "user005@example.test", "Tester005", "Surname005"});

        assertThat(resolver.authorLabel(5L)).isEqualTo("user005@example.test");
    }

    @Test
    @DisplayName("authorLabel returns null when the user has no email row -- deliberate parity")
    void authorLabelReturnsNullWhenAddressAbsent() {
        UserLabelResolver resolver =
                userResolver(new Object[] {110L, null, "Tester110", "Surname110"});

        // NOT "Unknown (110)", NOT "Unclassified" -- a bare null, exactly like the source
        // report's own AUTHOR column for a user with no email_addresses row.
        assertThat(resolver.authorLabel(110L)).isNull();
    }

    @Test
    @DisplayName("assigneeLabel falls back to lastname when there is no address")
    void assigneeLabelFallsBackToLastname() {
        UserLabelResolver resolver =
                userResolver(new Object[] {110L, null, "Tester110", "Surname110"});

        assertThat(resolver.assigneeLabel(110L)).isEqualTo("Surname110");
    }

    @Test
    @DisplayName("assigneeLabel prefers address over lastname when both are present")
    void assigneeLabelPrefersAddressOverLastname() {
        UserLabelResolver resolver = userResolver(
                new Object[] {5L, "user005@example.test", "Tester005", "Surname005"});

        assertThat(resolver.assigneeLabel(5L)).isEqualTo("user005@example.test");
    }

    @Test
    @DisplayName("assigneeLabel never returns null: Unclassified for no id, Unknown for an "
            + "unresolvable one")
    void assigneeLabelNeverReturnsNull() {
        UserLabelResolver resolver = userResolver(new Object[] {5L, "user005@example.test",
                "Tester005", "Surname005"});

        assertThat(resolver.assigneeLabel(null)).isEqualTo(EnumLabelResolver.UNCLASSIFIED);
        assertThat(resolver.assigneeLabel(999999L)).isEqualTo("Unknown (999999)");
    }

    @Test
    @DisplayName("developerLabel reads firstname, and never returns null")
    void developerLabelReadsFirstname() {
        UserLabelResolver resolver = userResolver(
                new Object[] {5L, "user005@example.test", "Tester005", "Surname005"});

        assertThat(resolver.developerLabel(5L)).isEqualTo("Tester005");
        assertThat(resolver.developerLabel(null)).isEqualTo(EnumLabelResolver.UNCLASSIFIED);
        assertThat(resolver.developerLabel(999999L)).isEqualTo("Unknown (999999)");
    }

    // ------------------------------------------------------------------ helpers

    /** Rows shaped {customFieldId, enumValueId, label}. */
    private EnumLabelResolver enumResolver(Object[]... rows) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Object[] row : rows) {
                handler.processRow(enumRow((Long) row[0], (Long) row[1], (String) row[2]));
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
        return new LocalRefEnumLabelResolver(jdbcTemplate);
    }

    /** Rows shaped {userId, address, firstname, lastname}. */
    private UserLabelResolver userResolver(Object[]... rows) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Object[] row : rows) {
                handler.processRow(userRow((Long) row[0], (String) row[1], (String) row[2],
                        (String) row[3]));
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
        return new LocalRefUserLabelResolver(jdbcTemplate);
    }

    private ResultSet enumRow(long customFieldId, long enumValueId, String label) {
        try {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("CUSTOM_FIELD_ID")).thenReturn(customFieldId);
            when(rs.getLong("ENUM_VALUE_ID")).thenReturn(enumValueId);
            when(rs.getString("LABEL")).thenReturn(label);
            return rs;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultSet userRow(long userId, String address, String firstname, String lastname) {
        try {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("USER_ID")).thenReturn(userId);
            when(rs.getString("ADDRESS")).thenReturn(address);
            when(rs.getString("FIRSTNAME")).thenReturn(firstname);
            when(rs.getString("LASTNAME")).thenReturn(lastname);
            return rs;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
