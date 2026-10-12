package id.co.yokke.incidentdashboard.support;

import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The ONE Oracle container shared by every integration test in this JVM.
 *
 * <p>Two things here are load-bearing and have already cost debugging time:
 *
 * <ol>
 *   <li><b>The import is {@code org.testcontainers.oracle.OracleContainer}</b> — the
 *       {@code oracle-free} module. There is a same-named
 *       {@code org.testcontainers.containers.OracleContainer} in the {@code oracle-xe}
 *       module; it is a different class targeting a different image family, and picking
 *       it up by IDE auto-import silently switches the database under the tests.
 *   <li><b>There is deliberately no {@code .withReuse(true)}.</b> Reuse requires a
 *       per-developer {@code ~/.testcontainers.properties} opt-in (so it is a no-op on
 *       CI and a footgun locally) and, worse, it carries state from a previous run into
 *       tests that assert exact row counts.
 * </ol>
 *
 * <p>One container per JVM — not per test class — because starting Oracle takes tens of
 * seconds. {@link AbstractOracleIT} therefore guards schema creation and Flyway behind a
 * one-shot latch so the second and subsequent IT classes do not re-run DDL.
 */
public final class OracleTestContainer {

    private static final DockerImageName IMAGE = DockerImageName.parse("gvenzl/oracle-free:23-slim");

    static {
        // docker-java (as shipped with Testcontainers 1.19.x) negotiates Docker API 1.32 by
        // default. OrbStack — and any daemon with `MinAPIVersion` at 1.40+ — rejects that with
        // "client version 1.32 is too old", which Testcontainers reports as the far less
        // helpful "Could not find a valid Docker environment". Pinning 1.41 is safe on every
        // daemon since Docker 20.10. Must be set BEFORE the container field below is
        // initialised, hence a separate static block placed above it.
        if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
            System.setProperty("api.version", "1.41");
        }
    }

    // NOTE: no .withDatabaseName(...) — the oracle-free module rejects it outright
    // ("Database name cannot be set to freepdb1"); the pluggable database is fixed at
    // FREEPDB1 by the image. Only the application user is configurable.
    public static final OracleContainer INSTANCE = new OracleContainer(IMAGE)
            .withUsername("incident_dash")
            .withPassword("incident_dash")
            // Testcontainers' default startup wait is 60s. A COLD gvenzl/oracle-free:23-slim
            // uncompresses its data files on first boot and reaches "DATABASE IS READY TO
            // USE!" in ~95s on this hardware, so the default fails the ENTIRE tier-2 suite
            // with "Timed out waiting for log output" -- a failure that reads like a broken
            // test rather than a too-short wait. Five minutes is slack, not a fix for a slow
            // query: nothing waits the full timeout when the container comes up quickly.
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    static {
        INSTANCE.start();
        // One container, many test classes; stop it when the JVM (not the class) ends.
        Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::stop));
    }

    private OracleTestContainer() {
    }
}
