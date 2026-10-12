package id.co.yokke.incidentdashboard.domain.usecase.insights;

import java.util.Optional;

/** The default: no external call, no prose. */
public class NoopNarrator implements AiNarrator {

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public Optional<String> narrate(String task, String facts, String language) {
        return Optional.empty();
    }
}
