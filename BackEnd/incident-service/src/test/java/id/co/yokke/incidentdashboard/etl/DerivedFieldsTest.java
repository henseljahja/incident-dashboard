package id.co.yokke.incidentdashboard.etl;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import id.co.yokke.incidentdashboard.domain.model.SolutionType;
import id.co.yokke.incidentdashboard.domain.usecase.etl.DerivedFields;
import id.co.yokke.incidentdashboard.domain.usecase.etl.DerivedFields.DateParse;
import id.co.yokke.incidentdashboard.domain.usecase.etl.DerivedFields.SlaPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * Plain unit test: no Spring, no database. Covers the pure write-time derivations Task 11's
 * ETL will call. Fixture case ids (22868, 22878, 22882, 22885, 22891) refer to
 * {@code src/test/resources/fixtures/custom_values.csv}, hand-authored for this purpose.
 */
class DerivedFieldsTest {

    // ------------------------------------------------------------ SOLUTION (SC031)

    @Test
    @DisplayName("both dates absent (NULL/NULL or ''/'') classify to null")
    void bothDatesAbsentClassifyToNull() {
        assertThat(DerivedFields.solution(null, null)).isNull();
        assertThat(DerivedFields.solution("", "")).isNull();
    }

    @Test
    @DisplayName("close set, resolution absent -> Permanent Solution")
    void closeSetResolutionAbsentIsPermanent() {
        assertThat(DerivedFields.solution("2026-07-01", null)).isEqualTo(SolutionType.PERMANENT);
        assertThat(DerivedFields.solution("2026-07-01", "")).isEqualTo(SolutionType.PERMANENT);
    }

    @Test
    @DisplayName("close absent, resolution set -> Workaround Solution")
    void closeAbsentResolutionSetIsWorkaround() {
        assertThat(DerivedFields.solution(null, "2026-07-01")).isEqualTo(SolutionType.WORKAROUND);
        assertThat(DerivedFields.solution("", "2026-07-01")).isEqualTo(SolutionType.WORKAROUND);
    }

    @Test
    @DisplayName("equal close/resolution -> Permanent Solution")
    void equalDatesArePermanent() {
        assertThat(DerivedFields.solution("2026-07-01", "2026-07-01")).isEqualTo(SolutionType.PERMANENT);
    }

    @Test
    @DisplayName("close after resolution -> Workaround & Pemanent Solution (typo preserved verbatim)")
    void closeAfterResolutionIsWorkaroundAndPermanent() {
        String result = DerivedFields.solution("2026-07-09", "2026-07-03");

        assertThat(result).isEqualTo("Workaround & Pemanent Solution");
        assertThat(result).isEqualTo(SolutionType.WORKAROUND_AND_PERMANENT);
        // Loudly assert the typo is NOT "fixed" to the correctly-spelled "Permanent".
        assertThat(result).contains("Pemanent");
        assertThat(result).doesNotContain("Permanent");
    }

    @Test
    @DisplayName("close precedes resolution has no source branch -> null")
    void solutionReturnsNullWhenClosePrecedesResolution() {
        assertThat(DerivedFields.solution("2026-07-01", "2026-07-09")).isNull();
    }

    @Test
    @DisplayName("solutionClassifierMatchesTheSourceReportExactly: the full parity table")
    void solutionClassifierMatchesTheSourceReportExactly() {
        assertThat(DerivedFields.solution(null, null)).isNull();
        assertThat(DerivedFields.solution("", "")).isNull();
        assertThat(DerivedFields.solution(null, "")).isEqualTo(SolutionType.WORKAROUND);
        assertThat(DerivedFields.solution("", null)).isEqualTo(SolutionType.PERMANENT);
        assertThat(DerivedFields.solution(null, "2026-07-02")).isEqualTo(SolutionType.WORKAROUND);
        assertThat(DerivedFields.solution("", "2026-07-02")).isEqualTo(SolutionType.WORKAROUND);
        assertThat(DerivedFields.solution("2026-07-01", null)).isEqualTo(SolutionType.PERMANENT);
        assertThat(DerivedFields.solution("2026-07-01", "")).isEqualTo(SolutionType.PERMANENT);
        assertThat(DerivedFields.solution("2026-07-01", "2026-07-01")).isEqualTo(SolutionType.PERMANENT);
        assertThat(DerivedFields.solution("2026-07-09", "2026-07-03")).isEqualTo(SolutionType.WORKAROUND_AND_PERMANENT);
        assertThat(DerivedFields.solution("2026-07-01", "2026-07-09")).isNull();
    }

    @Test
    @DisplayName("reproducesTheNullVersusEmptyStringQuirkInBothDirections: asymmetric, not a mirror")
    void reproducesTheNullVersusEmptyStringQuirkInBothDirections() {
        // close IS NULL, res = '' -- res IS NOT NULL holds for '', so branch 2 matches.
        assertThat(DerivedFields.solution(null, "")).isEqualTo("Workaround Solution");
        // close = '', res IS NULL -- branch 2's "res <> ''" is UNKNOWN for a NULL res, so it
        // falls through to branch 3 ("res IS NULL AND close IS NOT NULL"). NOT symmetric.
        assertThat(DerivedFields.solution("", null)).isEqualTo("Permanent Solution");
    }

    @Test
    @DisplayName("fixture 22868: close == resolution -> Permanent Solution")
    void fixture22868CloseEqualsResolutionIsPermanent() {
        assertThat(DerivedFields.solution("2026-07-01", "2026-07-01")).isEqualTo(SolutionType.PERMANENT);
    }

    @Test
    @DisplayName("fixture 22878: close row empty, resolution set -> Workaround Solution")
    void fixture22878CloseEmptyResolutionSetIsWorkaround() {
        assertThat(DerivedFields.solution("", "2026-07-02")).isEqualTo(SolutionType.WORKAROUND);
    }

    @Test
    @DisplayName("fixture 22882: close after resolution -> Workaround & Pemanent Solution")
    void fixture22882CloseAfterResolutionIsWorkaroundAndPermanent() {
        assertThat(DerivedFields.solution("2026-07-09", "2026-07-03")).isEqualTo(SolutionType.WORKAROUND_AND_PERMANENT);
    }

    // ------------------------------------------------------------ SLA_DAYS

    @Test
    @DisplayName("slaDaysFollowsThePriorityLadder: Low 60, Medium 14, High 7, Critical 3, keyed on id")
    void slaDaysFollowsThePriorityLadder() {
        SlaPolicy policy = seededPolicy();

        assertThat(DerivedFields.slaDays(1L, policy)).isEqualTo(60);  // Low
        assertThat(DerivedFields.slaDays(19L, policy)).isEqualTo(14); // Medium
        assertThat(DerivedFields.slaDays(3L, policy)).isEqualTo(7);   // High
        assertThat(DerivedFields.slaDays(21L, policy)).isEqualTo(3);  // Critical
    }

    @Test
    @DisplayName("unknownPriorityGetsTheStrictestSlaAndIsLogged")
    void unknownPriorityGetsTheStrictestSlaAndIsLogged() {
        int days = DerivedFields.slaDays(9999L, seededPolicy());

        assertThat(days).isEqualTo(3);
        assertThat(warnEvents()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("9999");
        });
    }

    @Test
    @DisplayName("a null priority id also falls back to 3 and is logged, never NPEs")
    void nullPriorityIdFallsBackAndIsLogged() {
        assertThat(DerivedFields.slaDays(null, seededPolicy())).isEqualTo(3);
        assertThat(warnEvents()).isNotEmpty();
    }

    @Test
    @DisplayName("renamingAPriorityDoesNotChangeItsSla: the API never takes a name at all")
    void renamingAPriorityDoesNotChangeItsSla() {
        // slaDays(Long, SlaPolicy) is structurally incapable of keying on a priority name --
        // it only ever sees the id. Two policies built under different "admin renamed the
        // priority" scenarios but with the same id->days mapping must agree.
        SlaPolicy beforeRename = SlaPolicy.of(Map.of(1L, 60));
        SlaPolicy afterRename = SlaPolicy.of(Map.of(1L, 60)); // same id, "Low" renamed to "Lowish"

        assertThat(DerivedFields.slaDays(1L, beforeRename))
                .isEqualTo(DerivedFields.slaDays(1L, afterRename))
                .isEqualTo(60);
    }

    // ------------------------------------------------------------ SLA_AT_RISK_DAYS (V9)

    @Test
    @DisplayName("the at-risk cushion follows the priority ladder: Low 7, Medium 3, High 2, Critical 1")
    void atRiskCushionFollowsThePriorityLadder() {
        SlaPolicy policy = seededPolicyWithCushions();

        assertThat(DerivedFields.slaAtRiskDays(1L, policy)).isEqualTo(7);   // Low
        assertThat(DerivedFields.slaAtRiskDays(19L, policy)).isEqualTo(3);  // Medium
        assertThat(DerivedFields.slaAtRiskDays(3L, policy)).isEqualTo(2);   // High
        assertThat(DerivedFields.slaAtRiskDays(21L, policy)).isEqualTo(1);  // Critical
        // The days half of the same policy is unchanged by carrying cushions.
        assertThat(DerivedFields.slaDays(3L, policy)).isEqualTo(7);
        assertThat(warnEvents()).as("a configured priority logs nothing").isEmpty();
    }

    @Test
    @DisplayName("TM009 an unknown priority defaults to SLA 3 / cushion 1, and the cushion WARN names the id")
    void unknownPriorityDefaultsAtRiskCushion() {
        SlaPolicy policy = seededPolicyWithCushions();

        assertThat(DerivedFields.slaDays(9999L, policy)).isEqualTo(3);
        logAppender.list.clear();

        assertThat(DerivedFields.slaAtRiskDays(9999L, policy))
                .isEqualTo(DerivedFields.UNKNOWN_PRIORITY_AT_RISK_DAYS)
                .isEqualTo(1);
        assertThat(warnEvents())
                .as("exactly one WARN line for the cushion fallback, naming the priority id")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("9999");
                });

        // A days-only policy (no cushion map) and a null id take the same fallback, never NPE.
        assertThat(DerivedFields.slaAtRiskDays(3L, seededPolicy())).isEqualTo(1);
        assertThat(DerivedFields.slaAtRiskDays(null, policy)).isEqualTo(1);
    }

    private SlaPolicy seededPolicyWithCushions() {
        return SlaPolicy.of(
                Map.of(1L, 60, 19L, 14, 3L, 7, 21L, 3),
                Map.of(1L, 7, 19L, 3, 3L, 2, 21L, 1));
    }

    private SlaPolicy seededPolicy() {
        return SlaPolicy.of(Map.of(
                1L, 60,
                19L, 14,
                3L, 7,
                21L, 3));
    }

    // ------------------------------------------------------------ CREATE_MONTH

    @Test
    @DisplayName("createMonth formats YYYYMM, including across a year boundary")
    void createMonthAcrossAYearBoundary() {
        assertThat(DerivedFields.createMonth(LocalDateTime.of(2025, 12, 31, 23, 59, 59)))
                .isEqualTo("202512");
        assertThat(DerivedFields.createMonth(LocalDateTime.of(2026, 1, 1, 0, 0, 0)))
                .isEqualTo("202601");
    }

    // ------------------------------------------------------------ parseRedmineDate

    @ParameterizedTest(name = "\"{0}\" parses to {1}")
    @CsvSource({
            "2026-07-01, 2026-07-01",
            "2021-01-12, 2021-01-12",
            "2021-09-08, 2021-09-08"
    })
    @DisplayName("parses the YYYY-MM-DD format found in the real data and the fixture")
    void parsesRealDataFormat(String raw, String expected) {
        DateParse result = DerivedFields.parseRedmineDate(raw);

        assertThat(result.rejected()).isFalse();
        assertThat(result.reason()).isNull();
        assertThat(result.value()).isEqualTo(LocalDate.parse(expected));
    }

    @Test
    @DisplayName("null and empty string both mean 'no date', distinguishably not-rejected")
    void nullAndEmptyBothMeanNoDate() {
        DateParse fromNull = DerivedFields.parseRedmineDate(null);
        DateParse fromEmpty = DerivedFields.parseRedmineDate("");

        assertThat(fromNull.value()).isNull();
        assertThat(fromNull.rejected()).isFalse();
        assertThat(fromEmpty.value()).isNull();
        assertThat(fromEmpty.rejected()).isFalse();
    }

    @Test
    @DisplayName("parsesRedmineDatesAndQuarantinesTheRest: garbage and calendar-invalid dates reject")
    void parsesRedmineDatesAndQuarantinesTheRest() {
        DateParse garbage = DerivedFields.parseRedmineDate("not-a-date");
        DateParse calendarInvalid = DerivedFields.parseRedmineDate("2025-02-31");

        assertThat(garbage.rejected()).isTrue();
        assertThat(garbage.value()).isNull();
        assertThat(garbage.reason()).isNotBlank();

        // STRICT resolver style + the "uuuu" pattern must reject, not roll forward to March 3.
        assertThat(calendarInvalid.rejected()).isTrue();
        assertThat(calendarInvalid.value()).isNull();
    }

    @Test
    @DisplayName("fixture 22885: cf 105 = 'not-a-date' -> quarantined, not silently nulled")
    void fixture22885UnparseableCloseDateIsQuarantined() {
        DateParse result = DerivedFields.parseRedmineDate("not-a-date");

        assertThat(result.rejected()).isTrue();
        assertThat(result.value()).isNull();
    }

    @Test
    @DisplayName("fixture 22891: migration date (cf 110) parses standalone, no close/resolution rows")
    void fixture22891MigrationDateOnly() {
        DateParse migration = DerivedFields.parseRedmineDate("2026-07-05");

        assertThat(migration.rejected()).isFalse();
        assertThat(migration.value()).isEqualTo(LocalDate.of(2026, 7, 5));
        // No close_date/resolution_date rows at all for this issue -> both absent -> null.
        assertThat(DerivedFields.solution(null, null)).isNull();
    }

    // ------------------------------------------------------------------ log capture

    private ListAppender<ILoggingEvent> logAppender;
    private Logger derivedFieldsLogger;

    @BeforeEach
    void attachLogAppender() {
        derivedFieldsLogger = (Logger) LoggerFactory.getLogger(DerivedFields.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        derivedFieldsLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        derivedFieldsLogger.detachAppender(logAppender);
    }

    private java.util.List<ILoggingEvent> warnEvents() {
        return logAppender.list;
    }
}
