package id.co.yokke.incidentdashboard.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * A stand-in for an OpenAI-compatible {@code /chat/completions} endpoint, so the real narrator
 * code path (prompt building, HTTP, JSON parsing, the SPA) runs end to end without a key or any
 * network. It answers with bullets that name the task and language it was asked for, which is
 * exactly what a test needs to prove the right request was made. It never sees real data.
 */
public final class FakeOpenAiServer implements AutoCloseable {

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

    public FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String system = request.path("messages").path(0).path("content").asText();
            String user = request.path("messages").path(1).path("content").asText();
            prompts.add(user);
            String task = user.lines().findFirst().orElse("").replace("Task: ", "");
            String lang = system.contains("Indonesian") ? "id" : "en";
            String content = "- [fake-llm] task=" + task + " lang=" + lang + "\n"
                    + "- Second point written by the stand-in model.";
            byte[] body = mapper.writeValueAsBytes(mapper.createObjectNode().set("choices",
                    mapper.createArrayNode().add(mapper.createObjectNode().set("message",
                            mapper.createObjectNode().put("content", content)))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every user prompt received so far, oldest first -- what would have left the machine. */
    public java.util.List<String> prompts() {
        return prompts;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
