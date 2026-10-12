package id.co.yokke.incidentdashboard.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import id.co.yokke.incidentdashboard.domain.usecase.insights.IncidentFact;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsResponse;
import id.co.yokke.incidentdashboard.domain.usecase.insights.OpenAiCompatibleNarrator;
import id.co.yokke.incidentdashboard.domain.usecase.insights.PatternAnalyzer;
import id.co.yokke.incidentdashboard.domain.usecase.insights.QaEngine;
import id.co.yokke.incidentdashboard.domain.usecase.insights.TextRelevance;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiFeaturesTest {

    private static IncidentFact t(long no, String subject, String prio, boolean closed,
            Integer aging, boolean breached, String source, String institusi, String category,
            String created, boolean workaround, boolean rootCause, String solution) {
        LocalDate d = LocalDate.parse(created);
        return new IncidentFact(no, subject, prio, closed ? "Closed" : "Open", institusi, source,
                "a@x", category, d, closed ? d.plusDays(aging == null ? 0 : aging) : null, closed,
                breached, false, aging, 7, solution, rootCause, workaround);
    }

    private static final List<IncidentFact> FACTS = List.of(
            t(1, "Transaksi QRIS BNI mengalami timeout", "Low", true, 10, false, "External",
                    "BNI", "Payment", "2026-04-01", true, false, "Workaround Solution"),
            t(2, "Transaksi Credit BNI mengalami peningkatan timeout", "Low", true, 12, false,
                    "External", "BNI", "Payment", "2026-04-05", true, false, "Workaround Solution"),
            t(3, "Transaksi BNI timeout EDC", "High", false, 20, true, "External", "BNI",
                    "Payment", "2026-04-09", false, false, null),
            t(4, "Kendala Payment LM GL to CASA", "Low", false, 9, false, "Internal",
                    "Livin Merchant", "Payment", "2026-04-10", false, true, null),
            t(5, "Jalin reject transaksi", "Medium", true, 4, false, "Internal", "JALIN",
                    "Network", "2026-04-11", false, true, "Permanent Solution"));

    @Test
    @DisplayName("Q&A: average aging of an institusi, in Indonesian")
    void qaAverageAgingOfInstitusi() {
        InsightsResponse.Answer a = QaEngine.ask("Berapa rata-rata aging incident BNI?", FACTS);

        assertThat(a.understood()).isTrue();
        assertThat(a.metric()).isEqualTo("AVG_AGING");
        assertThat(a.scope()).isEqualTo("BNI");
        assertThat(a.matched()).isEqualTo(3);
        assertThat(a.value()).isEqualTo(14.0); // (10 + 12 + 20) / 3
        assertThat(a.unit()).isEqualTo("d");
    }

    @Test
    @DisplayName("Q&A: counts, SLA metrics and combined filters, in English")
    void qaOtherMetrics() {
        assertThat(QaEngine.ask("how many external tickets", FACTS).value()).isEqualTo(3.0);
        assertThat(QaEngine.ask("how many open High incidents for BNI", FACTS).scope())
                .isEqualTo("BNI / High");
        assertThat(QaEngine.ask("SLA compliance", FACTS).value()).isEqualTo(100.0);
        assertThat(QaEngine.ask("closure rate", FACTS).value()).isEqualTo(60.0);
        assertThat(QaEngine.ask("MTTR for BNI", FACTS).value()).isEqualTo(11.0);
        assertThat(QaEngine.ask("berapa yang breach", FACTS).value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Q&A: an unrecognised question is reported as not understood, never guessed")
    void qaNotUnderstood() {
        InsightsResponse.Answer a = QaEngine.ask("what colour is the sky", FACTS);

        assertThat(a.understood()).isFalse();
        assertThat(a.value()).isNull();
        assertThat(QaEngine.ask(null, FACTS).understood()).isFalse();
    }

    @Test
    @DisplayName("search ranks lexical matches and ignores documents with no shared term")
    void relevanceSearch() {
        List<String> docs = FACTS.stream().map(IncidentFact::subject).toList();
        List<TextRelevance.Hit> hits = TextRelevance.search(docs, "timeout BNI", 10);

        assertThat(hits).extracting(TextRelevance.Hit::index).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(hits.get(0).score()).isGreaterThanOrEqualTo(hits.get(1).score());
        assertThat(hits).allSatisfy(h -> assertThat(h.score()).isBetween(0.0, 1.0001));
        assertThat(TextRelevance.search(docs, "zzzz", 10)).isEmpty();
        assertThat(TextRelevance.search(docs, "   ", 10)).isEmpty();
        // light stemming: "timeouts" finds "timeout"
        assertThat(TextRelevance.search(docs, "timeouts", 10)).isNotEmpty();
    }

    @Test
    @DisplayName("recurring patterns: same institusi + category, at least three times")
    void patterns() {
        List<InsightsResponse.Pattern> patterns = PatternAnalyzer.analyze(FACTS, 10);

        assertThat(patterns).singleElement().satisfies(p -> {
            assertThat(p.institusi()).isEqualTo("BNI");
            assertThat(p.category()).isEqualTo("Payment");
            assertThat(p.count()).isEqualTo(3);
            assertThat(p.firstSeen()).isEqualTo("2026-04-01");
            assertThat(p.lastSeen()).isEqualTo("2026-04-09");
            assertThat(p.closedWithWorkaround()).isEqualTo(2);
            assertThat(p.permanentSolution()).isZero();
            assertThat(p.recommendation()).isEqualTo("PERMANENT_FIX");
            assertThat(p.keywords()).contains("timeout");
            assertThat(p.samples().get(0).noIncident()).isEqualTo(3); // most recent first
        });
    }

    @Test
    @DisplayName("the OpenAI-compatible narrator sends only the given facts and returns the text")
    void narratorRoundTrip() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\" - 3 tickets \"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            var narrator = new OpenAiCompatibleNarrator(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "k-123",
                    "test-model", Duration.ofSeconds(5));

            assertThat(narrator.narrate("daily-summary", "{\"created\":3}", "id"))
                    .contains("- 3 tickets");
            assertThat(auth.get()).isEqualTo("Bearer k-123");
            assertThat(body.get()).contains("\"model\":\"test-model\"").contains("created")
                    .contains("Indonesian");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a provider failure yields no text rather than an exception")
    void narratorFailure() {
        var narrator = new OpenAiCompatibleNarrator("http://127.0.0.1:1/v1", "k", "m",
                Duration.ofSeconds(1));

        assertThat(narrator.narrate("x", "{}", "en")).isEmpty();
        assertThat(new ArrayList<>(List.of(narrator.provider()))).containsExactly("openai");
    }
}
