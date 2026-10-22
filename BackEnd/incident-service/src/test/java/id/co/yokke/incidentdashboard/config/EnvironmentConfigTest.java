package id.co.yokke.incidentdashboard.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Proves the deployment contract: the shipped {@code application.yml}, resolved with ONLY
 * environment variables (as in a container), yields the documented keys. A setting that stops
 * honouring its env var -- a rename, a hardcoded value -- fails here instead of in a cluster.
 */
class EnvironmentConfigTest {

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        // Skip the `scaffold` profile document: this is the default-profile view.
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            if (!source.getName().contains("scaffold")
                    && !String.valueOf(source.getProperty("spring.config.activate.on-profile"))
                            .equals("scaffold")) {
                environment.getPropertySources().addLast(source);
            }
        }
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private static <T> T bind(StandardEnvironment env, String key, Class<T> type) {
        return Binder.get(env).bind(key, type).orElse(null);
    }

    @Test
    @DisplayName("with no environment the documented defaults apply")
    void defaults() throws IOException {
        StandardEnvironment env = environment(Map.of());

        assertThat(bind(env, "incident.ai.enabled", Boolean.class)).isFalse();
        assertThat(bind(env, "incident.etl.scheduling-enabled", Boolean.class)).isTrue();
        assertThat(bind(env, "incident.etl.refresh-interval-ms", Long.class)).isEqualTo(120000L);
        assertThat(bind(env, "incident.mirror.schema", String.class)).isEqualTo("REDMINE_MIRROR.");
        assertThat(bind(env, "incident.cors.allowed-origins", String.class))
                .isEqualTo("http://localhost:5175");
        assertThat(bind(env, "incident.export.max-rows", Integer.class)).isEqualTo(50000);
        assertThat(bind(env, "server.port", Integer.class)).isEqualTo(8080);
        assertThat(bind(env, "incident.etl.reconcile-cron", String.class))
                .isEqualTo("0 15 * * * *");
        // Secrets have no default: a missing one must fail startup, not fall back.
        assertThat(bind(env, "incident.jwt.secret", String.class)).isNull();
        assertThat(bind(env, "spring.datasource.url", String.class)).isNull();
    }

    @Test
    @DisplayName("every documented environment variable overrides its setting")
    void environmentOverrides() throws IOException {
        Map<String, Object> vars = new java.util.LinkedHashMap<>();
        vars.put("INCIDENT_AI_ENABLED", "true");
        vars.put("INCIDENT_AI_TIMEOUT_SECONDS", "45");
        vars.put("INCIDENT_ETL_SCHEDULING_ENABLED", "false");
        vars.put("INCIDENT_ETL_REFRESH_INTERVAL_MS", "30000");
        vars.put("INCIDENT_ETL_CHUNK_SIZE", "250");
        vars.put("INCIDENT_ETL_RECONCILE_CRON", "0 30 2 * * *");
        vars.put("INCIDENT_MIRROR_SCHEMA", "REDMINE_MIRROR_");
        vars.put("INCIDENT_CORS_ALLOWED_ORIGINS", "https://incident.example.test");
        vars.put("INCIDENT_EXPORT_MAX_ROWS", "1000");
        vars.put("SERVER_PORT", "9090");
        vars.put("INCIDENT_JWT_SECRET", "x".repeat(40));
        vars.put("SPRING_DATASOURCE_URL", "jdbc:oracle:thin:@//db:1521/SVC");
        StandardEnvironment env = environment(vars);

        assertThat(bind(env, "incident.ai.enabled", Boolean.class)).isTrue();
        assertThat(bind(env, "incident.ai.timeout-seconds", Integer.class)).isEqualTo(45);
        assertThat(bind(env, "incident.etl.scheduling-enabled", Boolean.class)).isFalse();
        assertThat(bind(env, "incident.etl.refresh-interval-ms", Long.class)).isEqualTo(30000L);
        assertThat(bind(env, "incident.etl.chunk-size", Integer.class)).isEqualTo(250);
        assertThat(bind(env, "incident.etl.reconcile-cron", String.class)).isEqualTo("0 30 2 * * *");
        assertThat(bind(env, "incident.mirror.schema", String.class)).isEqualTo("REDMINE_MIRROR_");
        // The Flyway placeholder follows the mirror schema: they must never disagree.
        assertThat(env.resolvePlaceholders("${spring.flyway.placeholders.mirror}"))
                .isEqualTo("REDMINE_MIRROR_");
        assertThat(bind(env, "incident.cors.allowed-origins", String.class))
                .isEqualTo("https://incident.example.test");
        assertThat(bind(env, "incident.export.max-rows", Integer.class)).isEqualTo(1000);
        assertThat(bind(env, "server.port", Integer.class)).isEqualTo(9090);
        assertThat(bind(env, "incident.jwt.secret", String.class)).hasSize(40);
        assertThat(bind(env, "spring.datasource.url", String.class))
                .isEqualTo("jdbc:oracle:thin:@//db:1521/SVC");
    }
}
