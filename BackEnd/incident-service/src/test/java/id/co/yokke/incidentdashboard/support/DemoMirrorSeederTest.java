package id.co.yokke.incidentdashboard.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Populates the MIRROR tables of an EXTERNAL Oracle — the demo compose stack — from the same
 * fixture CSVs tier 2 and tier 3 use.
 *
 * <p><b>This is not a test and asserts almost nothing.</b> Like {@link E2eStackLauncherTest} it is
 * a loader wearing a JUnit costume, because {@link MirrorFixtureLoader} and the fixture CSVs live
 * on the TEST classpath. Reimplementing the mirror DDL in a shell or SQL script would be a second,
 * drifting definition of the same seven tables; reusing the one tier 2 already exercises every run
 * cannot drift.
 *
 * <p>It seeds the MIRROR ONLY. {@code INCIDENT_FACT} is then built by the service's own scheduled
 * ETL on its next tick — the real path, not a bypass — which is why the compose file leaves
 * {@code INCIDENT_ETL_SCHEDULING_ENABLED=true} with a shortened interval.
 *
 * <h2>Two phases, because Flyway has to run between them</h2>
 *
 * {@link AbstractOracleIT} documents the ordering that tier 2 depends on, and it is a THREE-step
 * sequence, not two:
 *
 * <ol>
 *   <li>{@code createMirrorSchema()} — {@code V3__mart.sql} declares {@code V_INCIDENT} as a view
 *       over the mirror tables, so Flyway fails with ORA-00942 if they do not exist yet.</li>
 *   <li>Flyway — in the demo stack this runs inside the service at startup, not here.</li>
 *   <li>{@code loadAll()} — the fixture set includes {@code ref_custom_field_enum} and
 *       {@code ref_user}, whose target tables are created by {@code V1__config_tables.sql}. Load
 *       before Flyway and it dies with ORA-00942 on those two.</li>
 * </ol>
 *
 * Hence {@code DEMO_SEED_PHASE}: {@code schema} runs step 1, {@code data} runs step 3, and
 * {@code deploy/start-demo.sh} starts the service in between. Splitting the phases is what lets
 * the demo use the service's OWN Flyway run rather than a second, separately-configured one.
 *
 * <p>One consequence worth knowing: step 1 leaves a non-empty schema, so the service's Flyway
 * must be configured with {@code baseline-on-migrate=true} and {@code baseline-version=0} or it
 * refuses to migrate. The compose file sets both, for the same reason AbstractOracleIT does.
 *
 * <p>Disabled unless {@code DEMO_SEED=true}, so a normal {@code ./mvnw test} skips both phases
 * rather than attempting to connect to a database that is not there.
 *
 * <h2>The fixtures are production-derived</h2>
 *
 * {@code issues.csv}, {@code journals.csv}, {@code journal_details.csv}, {@code issue_statuses.csv},
 * {@code enumerations.csv} and {@code trackers.csv} are extracted from the real Redmine database
 * and carry real customer names, staff names and incident descriptions. They are gitignored and
 * must stay that way. They are read from the local working copy at seed time and are deliberately
 * NOT copied into any container image — see the Dockerfiles, which build from source and never
 * include {@code src/test/resources/fixtures}.
 */
@EnabledIfEnvironmentVariable(named = "DEMO_SEED", matches = "true")
class DemoMirrorSeederTest {

    private static DataSource dataSource() throws Exception {
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(require("DEMO_DB_URL"));
        ds.setUser(require("DEMO_DB_USER"));
        ds.setPassword(require("DEMO_DB_PASSWORD"));
        return ds;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set. Run this through "
                    + "deploy/seed-demo.sh, which supplies it from deploy/.env.");
        }
        return value;
    }

    /** Step 1: mirror DDL only. Must complete BEFORE the service starts and runs Flyway. */
    @Test
    @DisplayName("phase 'schema': create the mirror tables")
    @EnabledIfEnvironmentVariable(named = "DEMO_SEED_PHASE", matches = "schema")
    void createMirrorSchema() throws Exception {
        DataSource dataSource = dataSource();

        // Drops and recreates all seven mirror tables. Idempotent, so re-running against an
        // already-seeded stack simply resets it.
        MirrorFixtureLoader.createMirrorSchema(dataSource);

        System.out.println("DEMO MIRROR SCHEMA CREATED — start the service now so its Flyway "
                + "run can create V_INCIDENT over these tables, then run phase 'data'.");
        System.out.flush();
    }

    /** Step 3: fixture rows. Must run AFTER Flyway, because two fixtures target REF_ tables. */
    @Test
    @DisplayName("phase 'data': load the fixture CSVs")
    @EnabledIfEnvironmentVariable(named = "DEMO_SEED_PHASE", matches = "data")
    void loadFixtureData() throws Exception {
        DataSource dataSource = dataSource();

        MirrorFixtureLoader.loadAll(dataSource);

        long issues = count(dataSource, MirrorFixtureLoader.mirrorTable("issues"));
        long journals = count(dataSource, MirrorFixtureLoader.mirrorTable("journals"));

        // A silent zero-row load would leave the dashboard empty and looking broken rather than
        // unseeded, so fail loudly here instead.
        if (issues == 0) {
            throw new IllegalStateException("mirror seeded 0 issues — check that the gitignored "
                    + "fixture CSVs are present in "
                    + "BackEnd/incident-service/src/test/resources/fixtures/");
        }

        System.out.println("DEMO MIRROR SEEDED  issues=" + issues + " journals=" + journals);
        System.out.println("The service's ETL will build INCIDENT_FACT on its next tick "
                + "(INCIDENT_ETL_REFRESH_INTERVAL_MS, 30s in the demo compose file).");
        System.out.flush();
    }

    private static long count(DataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
