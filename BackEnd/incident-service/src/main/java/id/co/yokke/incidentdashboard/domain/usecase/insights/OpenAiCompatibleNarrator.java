package id.co.yokke.incidentdashboard.domain.usecase.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chat-completions narrator for any OpenAI-compatible endpoint (OpenAI, Azure OpenAI gateway,
 * an in-house vLLM). Enabled only by {@code incident.ai.provider=openai} AND a non-blank
 * {@code incident.ai.api-key}.
 *
 * <h2>What leaves the process</h2>
 *
 * <p>Only the {@code facts} string handed in by {@code InsightsUseCase}: counts, percentages,
 * institusi and category names, and ticket NUMBERS. Ticket subjects are included only when
 * {@code incident.ai.send-subjects=true} (default false), because subjects carry customer and
 * staff names. No prompt, answer or fact is logged -- only the HTTP status on failure.
 */
public class OpenAiCompatibleNarrator implements AiNarrator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleNarrator.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleNarrator(String baseUrl, String apiKey, String model,
            Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.timeout = timeout;
    }

    private final Duration timeout;

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public Optional<String> narrate(String task, String facts, String language) {
        try {
            String system = "You write concise IT incident-management briefings for operations "
                    + "managers. Use ONLY the figures in the JSON; never invent numbers or "
                    + "ticket details. Answer in " + ("id".equals(language) ? "Indonesian"
                    : "English") + ", at most 120 words, as short bullet points.";
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", "Task: " + task + "\n" + facts))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("ai narrator: provider answered HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode text = mapper.readTree(response.body()).path("choices").path(0)
                    .path("message").path("content");
            return text.isTextual() && !text.asText().isBlank()
                    ? Optional.of(text.asText().trim()) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("ai narrator: call failed ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
