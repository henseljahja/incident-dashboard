package id.co.yokke.incidentdashboard.support;

import id.co.yokke.incidentdashboard.domain.usecase.etl.MartRefreshUseCase;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The tier-3 backend half: the isolated Testcontainers Oracle, migrated, seeded and served over
 * a real HTTP port so the Playwright suite in {@code FrontEnd/incident-dashboard/e2e} has
 * something genuine to drive. Task 23.
 *
 * <p>This exists because there is no {@code docker-compose.test.yml} in this repository and
 * there deliberately never was one: the isolated stack is {@link OracleTestContainer} (see
 * {@code AGENTS.md} -> {@code isolated_stack_file}), whose schema is created imperatively by
 * {@link AbstractOracleIT} and {@link MirrorFixtureLoader}. Reproducing that bootstrap in a
 * compose file would be a second, drifting source of truth for the same schema; reusing the
 * one that tier 2 already trusts cannot drift. It also means tier 3 can never be pointed at
 * {@code BackEnd/local-infra} — the DEV stack, whose data the fixtures would TRUNCATE.
 *
 * <p><b>This is not a test and asserts nothing.</b> It is a launcher wearing a JUnit costume,
 * because the bootstrap it needs (mirror schema, Flyway with the {@code ${mirror}} placeholder,
 * the TEST-classpath {@code V7__seed_test_users.sql}, the fixture CSVs) only exists on the test
 * classpath. It is disabled unless {@code E2E_STACK=true} is in the environment, so a normal
 * {@code ./mvnw test} skips it rather than hanging the build forever.
 *
 * <p>Run it, and leave it running, in its own terminal:
 *
 * <pre>
 *   cd BackEnd/incident-service
 *   E2E_STACK=true ./mvnw test -Dtest=E2eStackLauncherTest -DfailIfNoTests=false
 * </pre>
 *
 * <p>Cold start is a couple of minutes: {@code gvenzl/oracle-free:23-slim} needs ~95s before it
 * reports "DATABASE IS READY TO USE!", then Flyway, the fixture load and the first mart refresh
 * run. It prints {@code E2E STACK READY} when the Playwright suite may start. Ctrl-C to stop;
 * {@link OracleTestContainer}'s shutdown hook disposes the container.
 *
 * <p>{@code server.port=8081} pairs with the SPA's {@code E2E_API_BASE_URL} default, and
 * {@code incident.cors.allowed-origins} keeps {@code application.yml}'s {@code
 * http://localhost:5175} — which is why {@code playwright.config.ts} pins the dev server to
 * 5175 with {@code --strictPort}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
        "server.port=8081",
        // Explicit rather than inherited: the CORS allowlist is the one setting whose silent
        // drift makes every Playwright spec fail on an unrelated-looking cookie error.
        "incident.cors.allowed-origins=http://localhost:5175"})
@EnabledIfEnvironmentVariable(named = "E2E_STACK", matches = "true")
class E2eStackLauncherTest extends AbstractOracleIT {

    @Test
    @DisplayName("serve the seeded isolated stack on :8081 until interrupted")
    void serveUntilInterrupted() throws Exception {
        loadFixtures();
        seedScopedTrackers();
        seedSpoMatrix();

        // Populates INCIDENT_FACT (and therefore V_INCIDENT) from the mirror, and stamps
        // ETL_WATERMARK.LAST_RUN_AT so /api/freshness reports off a real run rather than a
        // cold start. The fixtures' own updated_on values are historic, so the mirror-lag half
        // of the staleness check still trips -- which is exactly the stale-banner path the
        // Playwright error-path spec walks.
        MartRefreshUseCase refresh = useCase(MartRefreshUseCase.class);
        MartRefreshUseCase.RefreshResult result = refresh.refresh();

        System.out.println("E2E STACK READY  http://localhost:8081/api"
                + "  (mart: scanned=" + result.scanned()
                + " changed=" + result.changed()
                + " rejected=" + result.rejected() + ")");
        System.out.flush();

        // Park. Ctrl-C ends it.
        new CountDownLatch(1).await();
    }

    /**
     * The shared CSV fixtures are Incident-only (tracker 7). The scoped-menus Playwright specs
     * need Problem (41) and Service Request (16/32/33) tickets too, so clone a few real mirror
     * issues under those trackers. Done here, not in the CSVs, because tier-2 ITs count the
     * fixture population and must not see it change. Ids are offset far above any real one.
     */
    private void seedScopedTrackers() throws Exception {
        String issues = MirrorFixtureLoader.mirrorTable("issues");
        String cols = "tracker_id, project_id, subject, description, due_date, category_id, "
                + "status_id, assigned_to_id, priority_id, fixed_version_id, author_id, "
                + "lock_version, created_on, updated_on, start_date, done_ratio, estimated_hours, "
                + "parent_id, rgt, lft, is_private, closed_on";
        // {tracker, source status}: 24 Analysis / 2 UAT / 37 UAT-RSLV / 46 UAT-DF are open stages,
        // 40 Closed Wkaround is closed, so every scope has both Open and Closed tickets.
        int[][] plan = {{41, 24}, {41, 2}, {41, 40}, {16, 37}, {16, 40}, {32, 46}, {32, 40}, {33, 2}};
        long id = 900_001;
        try (java.sql.Connection c = dataSource.getConnection();
                java.sql.Statement st = c.createStatement()) {
            for (int[] p : plan) {
                String select = cols.replace("tracker_id", String.valueOf(p[0]));
                st.executeUpdate("INSERT INTO " + issues + " (id, root_id, " + cols + ") "
                        + "SELECT " + id + ", " + id + ", " + select + " FROM (SELECT * FROM "
                        + issues + " WHERE id < 900000 AND status_id = " + p[1]
                        + " ORDER BY id FETCH FIRST 1 ROWS ONLY)");
                id++;
            }
            // Open Critical (21) and High (3) Incidents, so the wallboard's Critical/High list
            // has rows and the subject-visibility spec has something to look at.
            for (int priority : new int[] {21, 3}) {
                st.executeUpdate("INSERT INTO " + issues + " (id, root_id, " + cols + ") "
                        + "SELECT " + id + ", " + id + ", " + cols.replace("priority_id",
                                String.valueOf(priority)) + " FROM (SELECT * FROM " + issues
                        + " WHERE id < 900000 AND status_id = 24 ORDER BY id FETCH FIRST 1 ROWS ONLY)");
                id++;
            }
        }
    }

    /**
     * The CSV fixtures only know synthetic enum names ("Impact - Low"). The stakeholders' matrix
     * is Impact (Massive, Large, Limited, Localized) x Urgency (Urgent, High, Medium, Low), so add
     * those enum rows for custom fields 93 / 94 and give the first 18 Incidents a spread that hits
     * ALL 16 cells (two cells twice); everything else stays Unclassified.
     */
    private void seedSpoMatrix() throws Exception {
        String[] impacts = {"Massive", "Large", "Limited", "Localized"};
        String[] urgencies = {"Urgent", "High", "Medium", "Low"};
        String issues = MirrorFixtureLoader.mirrorTable("issues");
        String values = MirrorFixtureLoader.mirrorTable("custom_values");
        try (java.sql.Connection c = dataSource.getConnection();
                java.sql.Statement st = c.createStatement()) {
            for (int i = 0; i < 4; i++) {
                st.executeUpdate("INSERT INTO REF_CUSTOM_FIELD_ENUM (custom_field_id, enum_value_id,"
                        + " label, position) VALUES (93, " + (301 + i) + ", '" + impacts[i] + "', "
                        + (i + 1) + ")");
                st.executeUpdate("INSERT INTO REF_CUSTOM_FIELD_ENUM (custom_field_id, enum_value_id,"
                        + " label, position) VALUES (94, " + (311 + i) + ", '" + urgencies[i] + "', "
                        + (i + 1) + ")");
            }
            st.executeUpdate("DELETE FROM " + values + " WHERE custom_field_id IN (93, 94)");
            java.util.List<Long> ids = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = st.executeQuery("SELECT id FROM " + issues
                    + " WHERE tracker_id = 7 AND id < 900000 ORDER BY id FETCH FIRST 18 ROWS ONLY")) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            long valueId = 910_000;
            for (int n = 0; n < ids.size(); n++) {
                int cell = n % 16; // 16 distinct cells, then the first two again
                int impact = cell / 4;
                int urgency = cell % 4;
                for (int[] field : new int[][] {{93, 301 + impact}, {94, 311 + urgency}}) {
                    st.executeUpdate("INSERT INTO " + values + " (id, customized_type, customized_id,"
                            + " custom_field_id, value) VALUES (" + (valueId++) + ", 'Issue', "
                            + ids.get(n) + ", " + field[0] + ", '" + field[1] + "')");
                }
            }
        }
    }
}
