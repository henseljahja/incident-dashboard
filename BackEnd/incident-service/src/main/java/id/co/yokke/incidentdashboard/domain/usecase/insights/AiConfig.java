package id.co.yokke.incidentdashboard.domain.usecase.insights;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the {@link AiNarrator}. Anything other than {@code incident.ai.enabled=true} AND {@code incident.ai.provider=openai} with an
 * API key yields the no-op narrator: the safe default is that no ticket data ever leaves.
 */
@Configuration
public class AiConfig {

    @Bean
    public AiNarrator aiNarrator(
            @Value("${incident.ai.enabled:false}") boolean enabled,
            @Value("${incident.ai.provider:none}") String provider,
            @Value("${incident.ai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${incident.ai.api-key:}") String apiKey,
            @Value("${incident.ai.model:gpt-4o-mini}") String model,
            @Value("${incident.ai.timeout-seconds:20}") long timeoutSeconds) {
        if (enabled && "openai".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank()) {
            return new OpenAiCompatibleNarrator(baseUrl, apiKey, model,
                    Duration.ofSeconds(timeoutSeconds));
        }
        return new NoopNarrator();
    }
}
