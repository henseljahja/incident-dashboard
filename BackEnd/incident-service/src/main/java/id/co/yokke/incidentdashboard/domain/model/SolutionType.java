package id.co.yokke.incidentdashboard.domain.model;

/**
 * The literal {@code SOLUTION} strings produced by the source report query (see
 * {@code docs/reference/Query Detail Redmine Incident, Problem, SR.sql}, the {@code CASE}
 * expression aliased {@code SOLUTION}). These are compared against the delivered workbook —
 * they are not free text and must not be reworded.
 */
public final class SolutionType {

    public static final String WORKAROUND = "Workaround Solution";
    public static final String PERMANENT = "Permanent Solution";

    /**
     * DO NOT "FIX" THIS SPELLING. {@code Pemanent} is misspelled in the source query itself
     * and the delivered workbook reproduces the typo verbatim. Correcting it here would be a
     * parity failure against the workbook, not an improvement — the report's consumers match
     * on this exact string.
     */
    public static final String WORKAROUND_AND_PERMANENT = "Workaround & Pemanent Solution";

    private SolutionType() {
    }
}
