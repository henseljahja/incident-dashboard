package id.co.yokke.incidentdashboard.domain.usecase.config;

import java.time.Instant;
import java.util.List;

/** Wire and domain shapes of the adjustable parameters (V13). Values only; never ticket data. */
public final class ConfigModel {

    private ConfigModel() {
    }

    public record Param(String key, String type, String value, String defaultValue, Double min,
            Double max, Instant updatedAt, String updatedBy) {
    }

    /**
     * One alert rule. {@code threshold} is rule-specific (see V13): null for rules without one.
     */
    public record AlertRule(String code, boolean enabled, String severity, int minCount,
            Double threshold, boolean defaultEnabled, String defaultSeverity, int defaultMinCount,
            Double defaultThreshold, Instant updatedAt, String updatedBy) {

        public boolean hasThreshold() {
            return defaultThreshold != null;
        }
    }

    public record SlaItem(long priorityId, String priorityName, int slaDays,
            int atRiskRemainingDays) {
    }

    public record Change(Instant at, String by, String target, String oldValue, String newValue) {
    }

    public record Snapshot(List<SlaItem> slaPolicy, List<AlertRule> alertRules, List<Param> params,
            List<Change> recentChanges) {
    }

    public record SlaUpdate(long priorityId, int slaDays, int atRiskRemainingDays) {
    }

    public record AlertRuleUpdate(Boolean enabled, String severity, Integer minCount,
            Double threshold) {
    }

    /** Raised for every rejected edit; mapped to 400 with the message shown to the editor. */
    public static class ConfigException extends RuntimeException {

        private final String field;

        public ConfigException(String message, String field) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
