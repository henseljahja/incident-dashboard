package id.co.yokke.incidentdashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Context-load smoke test. Must pass with NO database reachable.
 *
 * <p>Runs under the {@code scaffold} profile, which is the only place that excludes
 * DataSource/HibernateJpa/Flyway autoconfiguration. That exclusion used to sit unconditionally in
 * the base {@code application.yml}, which meant a real deployment also came up with no DataSource
 * and never ran its migrations. Keeping it profile-scoped is what stops this test's convenience
 * from silently disabling production's schema creation.
 */
@SpringBootTest
@ActiveProfiles("scaffold")
class IncidentDashboardApplicationTests {

	@Test
	void contextLoads() {
	}

}
