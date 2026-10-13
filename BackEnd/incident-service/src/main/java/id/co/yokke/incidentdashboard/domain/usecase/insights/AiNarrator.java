package id.co.yokke.incidentdashboard.domain.usecase.insights;

import java.util.Optional;

/**
 * Optional prose layer over the structured insight results.
 *
 * <p>Every AI feature works WITHOUT a narrator: the numbers, rankings and rules are computed
 * locally and returned as data. A narrator only adds a short natural-language paragraph, and the
 * default implementation ({@link NoopNarrator}) adds nothing and sends nothing anywhere. A real
 * one is enabled only by explicit configuration ({@code incident.ai.provider}); see
 * {@link OpenAiCompatibleNarrator} for exactly what it sends.
 */
public interface AiNarrator {

    /** {@code local} when nothing external is involved, else the provider name. */
    String provider();

    /**
     * @param task what to write ({@code daily-summary}, {@code recurring-patterns})
     * @param facts the already-computed figures, as JSON text, aggregates only unless the
     *     operator opted into ticket subjects
     * @param language {@code en} or {@code id}
     * @return the paragraph, or empty when disabled or the provider failed
     */
    Optional<String> narrate(String task, String facts, String language);
}
