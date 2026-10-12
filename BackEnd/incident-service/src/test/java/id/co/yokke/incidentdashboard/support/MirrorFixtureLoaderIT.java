package id.co.yokke.incidentdashboard.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Guards the fixture harness every later integration test inherits. */
class MirrorFixtureLoaderIT extends AbstractOracleIT {

    @BeforeEach
    void loadFixtureData() {
        loadFixtures();
    }

    @Test
    @DisplayName("all seven mirror tables load from the extracted fixtures")
    void loadsAllSevenMirrorTables() {
        assertThat(count("ISSUES")).isEqualTo(100);
        assertThat(count("TRACKERS")).isEqualTo(5);
        assertThat(count("ISSUE_STATUSES")).isGreaterThan(40);
        assertThat(count("ENUMERATIONS")).isEqualTo(4);
        assertThat(count("CUSTOM_VALUES")).isPositive();
        assertThat(count("JOURNALS")).isPositive();
        assertThat(count("JOURNAL_DETAILS")).isPositive();
    }

    @Test
    @DisplayName("no custom_values row points at an issue outside the fixture")
    void everyCustomValueRowPointsAtAnIssueInTheFixture() {
        // Guards defect (b) in the source dump: 05. redmine.custom_values.sql was exported
        // with `WHERE id IN (<issue ids>)` instead of `WHERE customized_id IN (...)`, so all
        // 23 of its rows are unrelated to the sampled issues. If anyone ever regenerates
        // custom_values.csv from that dump, this assertion is what catches it.
        long orphans = scalar(
                "SELECT COUNT(*) FROM " + MirrorFixtureLoader.mirrorTable("custom_values") + " cv "
                        + "WHERE NOT EXISTS (SELECT 1 FROM "
                        + MirrorFixtureLoader.mirrorTable("issues") + " i "
                        + "WHERE i.\"ID\" = cv.\"CUSTOMIZED_ID\")");
        assertThat(orphans).isZero();
    }

    @Test
    @DisplayName("SQL NULL and the empty string survive the CSV round trip distinctly")
    void preservesNullVersusEmptyStringInCustomValues() {
        String table = MirrorFixtureLoader.mirrorTable("custom_values");

        // Row 900015 (issue 22885, field 106) is the \N sentinel -> genuine SQL NULL.
        long nulls = scalar("SELECT COUNT(*) FROM " + table
                + " WHERE \"ID\" = 900015 AND \"VALUE\" IS NULL");
        assertThat(nulls).isEqualTo(1);

        // Row 900009 (issue 22878, field 105 = close date) is an EMPTY FIELD -> a non-NULL,
        // zero-length CLOB. Oracle cannot express this as `VALUE = ''`: comparing to a
        // zero-length string is comparing to NULL and is never true. The empty LOB locator
        // is what carries "present but blank", and DBMS_LOB.GETLENGTH is how you see it.
        long empties = scalar("SELECT COUNT(*) FROM " + table
                + " WHERE \"ID\" = 900009 AND \"VALUE\" IS NOT NULL "
                + "AND DBMS_LOB.GETLENGTH(\"VALUE\") = 0");
        assertThat(empties).isEqualTo(1);

        // And the two really are different rows in different states — this is the assertion
        // that would fail if the loader collapsed '' to NULL.
        assertThat(nulls).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM " + table + " WHERE \"VALUE\" IS NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("resetAll leaves a clean table set for the next test method")
    void resetIsIdempotentAcrossTestMethods() {
        // Second method in the class to touch ISSUES: proves @BeforeEach resetAll() ran and
        // the re-load did not hit ORA-00001 on the primary key.
        assertThat(count("ISSUES")).isEqualTo(100);
    }

    private long count(String logical) {
        return scalar("SELECT COUNT(*) FROM " + MirrorFixtureLoader.MIRROR_PREFIX + logical);
    }

    private long scalar(String sql) {
        // Connection AND Statement both in the try-with-resources: closing only the Statement
        // leaks a pooled Connection per call and eventually hangs the suite.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }
}
