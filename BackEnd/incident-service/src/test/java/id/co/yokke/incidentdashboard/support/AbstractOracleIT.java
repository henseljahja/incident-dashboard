package id.co.yokke.incidentdashboard.support;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for every integration test that needs a real Oracle.
 *
 * <p>{@code @SpringBootTest} rather than a bare {@code DataSource} on purpose: later tasks
 * assert on real use-case beans (refresh, reconcile, freshness), not on raw SQL, so the
 * application context has to be up.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractOracleIT {

    /**
     * ONE latch guarding the mirror-schema + Flyway PAIR.
     *
     * <p><b>Ordering is load-bearing.</b> {@code createMirrorSchema()} must finish before
     * Flyway runs: {@code V3__mart.sql} creates {@code V_INCIDENT} as a view over the mirror
     * tables, so migrating first dies with ORA-00942. Guarding the two halves with separate
     * latches would let a second test class interleave them; guarding them as a unit cannot.
     *
     * <p>And the latch has to exist at all because the container is shared by every IT class
     * in the JVM — a second unguarded Flyway run dies with ORA-00955 (name already used).
     */
    private static volatile boolean bootstrapped = false;

    private static final Object BOOTSTRAP_LOCK = new Object();

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected DataSource dataSource;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OracleTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", OracleTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", OracleTestContainer.INSTANCE::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");

        // The scaffold's application.yml excludes the DataSource/JPA/Flyway autoconfigs
        // because no DataSource existed yet. Integration tests DO have one, so clear the
        // exclusion list here rather than editing the shared main config.
        registry.add("spring.autoconfigure.exclude", () -> "");

        // A table-name PREFIX, not a schema: Testcontainers gives us one schema, so mirror
        // tables live beside the app tables as REDMINE_MIRROR_ISSUES etc. Production uses a
        // real second schema, "REDMINE_MIRROR." — note that BOTH values carry their own
        // trailing separator. Consumers concatenate; they never add a dot.
        registry.add("incident.mirror.schema", () -> MirrorFixtureLoader.MIRROR_PREFIX);
        registry.add("spring.flyway.placeholders.mirror",
                () -> MirrorFixtureLoader.MIRROR_PREFIX);

        // >= 32 bytes: HS256 keys shorter than the digest are rejected by jjwt.
        registry.add("incident.jwt.secret",
                () -> "integration-test-signing-secret-0123456789abcdef");

        // Flyway is run MANUALLY below, once, after the mirror schema exists. Leaving Spring's
        // Flyway enabled would run a second, unplaceholdered migration behind our back —
        // before the mirror tables exist, and with ${mirror} unresolved.
        registry.add("spring.flyway.enabled", () -> "false");

        // NO SCHEDULER IN TESTS. SchedulingConfig is @ConditionalOnProperty on this flag, so
        // "false" means the @Configuration is never registered at all: no @EnableScheduling,
        // no TaskScheduler, no task. A background refresh tick would otherwise land between
        // resetAll()'s TRUNCATE and the fixture load and MERGE rows into a half-empty mirror,
        // producing intermittent tier-2 failures that read as real ETL bugs and move from
        // test to test between runs.
        //
        // This is ASSERTED, not assumed: ReconcileIT.schedulingIsDisabledUnderTest checks that
        // no SchedulingConfig bean and no scheduled task exist in the running context.
        registry.add("incident.etl.scheduling-enabled", () -> "false");
    }

    @BeforeEach
    void bootstrapAndReset() {
        bootstrap(dataSource);
        // Without this, the second test method in any class hits ORA-00001 on the ISSUES
        // primary key, because the container outlives the test method.
        MirrorFixtureLoader.resetAll(dataSource);
    }

    private static void bootstrap(DataSource dataSource) {
        if (bootstrapped) {
            return;
        }
        synchronized (BOOTSTRAP_LOCK) {
            if (bootstrapped) {
                return;
            }
            // Order inside the latch is the whole point: mirror tables first, migrations second.
            MirrorFixtureLoader.createMirrorSchema(dataSource);
            Flyway.configure()
                    .dataSource(dataSource)
                    // Direct consequence of the ordering above: creating the mirror tables
                    // first leaves a NON-EMPTY schema, and Flyway refuses to migrate one
                    // without a history table ("Found non-empty schema(s) ... but no schema
                    // history table"). Baselining is the sanctioned answer.
                    .baselineOnMigrate(true)
                    // baselineVersion MUST be 0, not Flyway's default of 1: at the default,
                    // the baseline marker sits at version 1 and V1__*.sql would be silently
                    // skipped for the rest of the project.
                    .baselineVersion("0")
                    .placeholders(Map.of("mirror", MirrorFixtureLoader.MIRROR_PREFIX))
                    .load()
                    .migrate();
            bootstrapped = true;
        }
    }

    /** Loads all fixture CSVs present on the classpath into the mirror tables. */
    protected void loadFixtures() {
        MirrorFixtureLoader.loadAll(dataSource);
    }

    /**
     * Lazy bean lookup for the use cases added in Tasks 11-12.
     *
     * <p>Deliberately NOT an {@code @Autowired} field. {@code RefreshMirrorUseCase},
     * {@code ReconcileUseCase} and {@code FreshnessUseCase} do not exist yet; an injected
     * field would fail context startup for <em>every</em> integration test written before
     * Phase 2, not just the ones that need them. Resolving the bean at use time keeps the
     * failure local to the test that actually asks for it.
     *
     * <p>Named accessors ({@code refreshUseCase()} and friends) are intentionally omitted
     * until those classes land — typing them today would mean either returning {@code Object}
     * (useless to callers) or creating stub production classes (worse). Call
     * {@code useCase(RefreshMirrorUseCase.class)} from Task 11 onwards, or add the typed
     * one-liner accessors then.
     */
    protected <T> T useCase(Class<T> type) {
        return context.getBean(type);
    }
}
