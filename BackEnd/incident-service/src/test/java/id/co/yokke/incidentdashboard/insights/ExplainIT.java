package id.co.yokke.incidentdashboard.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.yokke.incidentdashboard.support.AbstractOracleIT;
import id.co.yokke.incidentdashboard.support.FakeOpenAiServer;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Explain this" end to end through the real chain -- privilege interceptor, use case, narrator,
 * HTTP -- against a local stand-in model. The assertions that matter most are about what LEAVES:
 * the prompt must carry figures, never ticket subjects.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "incident.ai.enabled=true", "incident.ai.provider=openai",
        "incident.ai.api-key=test-not-a-key"})
class ExplainIT extends AbstractOracleIT {

    private static final FakeOpenAiServer LLM = start();

    private static FakeOpenAiServer start() {
        try {
            return new FakeOpenAiServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry registry) {
        registry.add("incident.ai.base-url", LLM::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String tokenFor(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
        }).get("token").toString();
    }

    private String ops() throws Exception {
        return tokenFor("ops.manager", "OpsManager#2026");
    }

    private JsonNode json(String path, String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("status reports a live narrator; each topic returns the model's text")
    void eachTopicIsExplained() throws Exception {
        String token = ops();
        JsonNode status = json("/api/insights/ai/status", token);
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("narrating").asBoolean()).isTrue();

        for (String topic : new String[] {"forecast", "alerts", "rca"}) {
            JsonNode e = json("/api/insights/ai/explain/" + topic, token);
            assertThat(e.get("topic").asText()).isEqualTo(topic);
            assertThat(e.get("narrative").asText()).contains("task=explain-" + topic)
                    .contains("lang=en");
            assertThat(e.get("provider").asText()).isEqualTo("openai");
        }
        assertThat(json("/api/insights/ai/explain/rca?lang=id", token).get("narrative").asText())
                .contains("lang=id");
    }

    @Test
    @DisplayName("the prompt carries figures only: no ticket subjects, no history arrays")
    void promptsNeverCarrySubjects() throws Exception {
        String token = ops();
        for (String topic : new String[] {"forecast", "alerts", "rca"}) {
            json("/api/insights/ai/explain/" + topic, token);
        }
        assertThat(LLM.prompts()).isNotEmpty().allSatisfy(p -> assertThat(p)
                .doesNotContain("\"subject\"").doesNotContain("\"history\"")
                .doesNotContain("\"days\""));
    }

    @Test
    @DisplayName("the forecast horizon is honoured and a bad one is clamped, not rejected")
    void forecastHorizon() throws Exception {
        String token = ops();
        json("/api/insights/ai/explain/forecast?horizonDays=14", token);
        assertThat(LLM.prompts().get(LLM.prompts().size() - 1)).contains("\"horizonDays\":14");
        json("/api/insights/ai/explain/forecast?horizonDays=999", token);
        assertThat(LLM.prompts().get(LLM.prompts().size() - 1)).contains("\"horizonDays\":30");
    }

    @Test
    @DisplayName("roles without the page's privilege are refused: 403, and nothing is sent")
    void privilegesAreEnforced() throws Exception {
        int before = LLM.prompts().size();
        String wallboard = tokenFor("wallboard.user", "Wallboard#2026");
        for (String topic : new String[] {"forecast", "alerts", "rca"}) {
            assertThat(mockMvc.perform(get("/api/insights/ai/explain/" + topic)
                            .header("Authorization", "Bearer " + wallboard))
                    .andReturn().getResponse().getStatus()).as(topic).isEqualTo(403);
        }
        // EXEC may see the forecast, but alerts and RCA carry row-level data.
        String exec = tokenFor("exec.user", "ExecUser#2026");
        assertThat(mockMvc.perform(get("/api/insights/ai/explain/alerts")
                        .header("Authorization", "Bearer " + exec))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/api/insights/ai/explain/rca")
                        .header("Authorization", "Bearer " + exec))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/api/insights/ai/explain/forecast"))
                .andReturn().getResponse().getStatus()).as("anonymous").isEqualTo(401);
        assertThat(LLM.prompts().size()).isEqualTo(before);
    }
}
