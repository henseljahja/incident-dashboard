package id.co.yokke.incidentdashboard.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.DefinitionsResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.KpiResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.MatrixResponse;
import id.co.yokke.incidentdashboard.infrastructure.rest.dto.res.TrendResponse;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one invariant of the metrics contract that needs no database: <b>there is no
 * detection-latency metric, and there never will be.</b>
 *
 * <p>Redmine records when a ticket was RAISED. It records nothing about when the incident
 * BEGAN — no monitoring alert timestamp, no detection event, nothing. A mean-time-to-detect
 * computed from this dataset would not be an approximation; it would be a number with no
 * referent, published on an executive dashboard next to figures that are real, and
 * indistinguishable from them.
 *
 * <p>It is easy to add by accident: the field sits one line away from the two mean-times that
 * ARE computable, and every incident-dashboard template on the internet has one. So this test
 * checks the wire contract AND the source text — a chart label, a javadoc heading or a
 * variable name would each be a claim we cannot support.
 */
class MetricsContractTest {

    /** The abbreviation, and the words a label would spell it out with. */
    private static final List<String> FORBIDDEN_ON_THE_WIRE =
            List.of("mttd", "timetodetect", "meantimetodetect", "detect");

    /** In source, only the abbreviation and the spelled-out metric name are banned. */
    private static final List<String> FORBIDDEN_IN_SOURCE =
            List.of("mttd", "timetodetect", "meantimetodetect");

    private static final List<String> METRICS_SOURCES = List.of(
            "src/main/java/id/co/yokke/incidentdashboard/domain/usecase/query/"
                    + "MetricsUseCase.java",
            "src/main/java/id/co/yokke/incidentdashboard/infrastructure/rest/"
                    + "MetricsController.java",
            "src/main/java/id/co/yokke/incidentdashboard/infrastructure/rest/dto/res/"
                    + "KpiResponse.java",
            "src/main/java/id/co/yokke/incidentdashboard/infrastructure/rest/dto/res/"
                    + "MatrixResponse.java",
            "src/main/java/id/co/yokke/incidentdashboard/infrastructure/rest/dto/res/"
                    + "TrendResponse.java",
            "src/main/java/id/co/yokke/incidentdashboard/infrastructure/rest/dto/res/"
                    + "DefinitionsResponse.java",
            "src/main/java/id/co/yokke/incidentdashboard/domain/usecase/query/"
                    + "DefinitionsUseCase.java");

    @Test
    @DisplayName("no endpoint exposes anything named or labelled MTTD")
    void noEndpointExposesAnythingNamedMttd() throws Exception {
        // 1. The field names, every record in the contract, nested ones included.
        List<String> fields = new ArrayList<>();
        collectComponents(KpiResponse.class, fields);
        collectComponents(MatrixResponse.class, fields);
        collectComponents(MatrixResponse.SlaBucket.class, fields);
        collectComponents(TrendResponse.class, fields);
        collectComponents(TrendResponse.TopCategory.class, fields);
        collectComponents(DefinitionsResponse.class, fields);
        collectComponents(DefinitionsResponse.SlaPolicyItem.class, fields);
        collectComponents(DefinitionsResponse.UnknownPriority.class, fields);
        collectComponents(DefinitionsResponse.ClosedStatus.class, fields);

        assertThat(fields)
                .as("a vacuous field list would make every assertion below pass")
                .isNotEmpty();
        for (String field : fields) {
            assertThat(normalise(field))
                    .as("field '" + field + "': detection latency is not computable from "
                            + "Redmine data and must not be named as if it were")
                    .doesNotContain("mttd")
                    .doesNotContain("timetodetect");
        }

        // 2. The serialised wire shape -- a @JsonProperty rename would slip past step 1.
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(sample());
        assertThat(json)
                .as("the sample must actually carry the two mean-times, or this proves "
                        + "nothing about a populated response")
                .contains("mttrWorkaroundDays")
                .contains("mttrPermanentDays");
        for (String forbidden : FORBIDDEN_ON_THE_WIRE) {
            assertThat(normalise(json))
                    .as("'" + forbidden + "' must not appear anywhere on the wire")
                    .doesNotContain(forbidden);
        }

        // 3. The source text -- a comment, a chart label or a local variable is still a claim.
        for (String source : METRICS_SOURCES) {
            String text = read(source);
            for (String forbidden : FORBIDDEN_IN_SOURCE) {
                assertThat(normalise(text))
                        .as(source + " must not mention '" + forbidden + "'")
                        .doesNotContain(forbidden);
            }
        }
    }

    /**
     * The positive half: the two mean-times that ARE computable are both on the contract. A
     * test that only forbids a field passes just as well when the whole KPI row is missing.
     */
    @Test
    @DisplayName("both computable mean-times, and the debt between them, are on the contract")
    void bothComputableMeanTimesAreOnTheContract() {
        List<String> fields = new ArrayList<>();
        collectComponents(KpiResponse.class, fields);

        assertThat(fields).contains("mttrWorkaroundDays", "mttrPermanentDays", "mttrGapDays",
                "workaroundDebt", "previousPeriod", "windowDays");
    }

    private static void collectComponents(Class<?> type, List<String> into) {
        assertThat(type.isRecord()).as(type.getSimpleName() + " is a record").isTrue();
        for (RecordComponent component : type.getRecordComponents()) {
            into.add(component.getName());
        }
    }

    private static String read(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        assertThat(Files.exists(path))
                .as(relativePath + " must exist, or this scan silently checks nothing")
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Case- and separator-insensitive, so {@code MTT_D} or {@code Mttd} cannot hide. */
    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static KpiResponse sample() {
        // backlog, open, closed (= backlog - open), breached, atRisk
        KpiResponse previous = new KpiResponse(LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 10), 10, 3, 2, 1, 1, 1, 2.0, 5.0, 3.0, 4.0, 1, null);
        return new KpiResponse(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 20), 10,
                4, 3, 1, 1, 1, 3.0, 6.0, 3.0, 5.0, 2, previous);
    }
}
