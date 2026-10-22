package id.co.yokke.incidentdashboard.domain.usecase.config;

import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRule;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.AlertRuleUpdate;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.Change;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.ConfigException;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.Param;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.SlaItem;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.SlaUpdate;
import id.co.yokke.incidentdashboard.domain.usecase.config.ConfigModel.Snapshot;
import id.co.yokke.incidentdashboard.domain.usecase.insights.ForecastModel;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The adjustable parameters: SLA ladder and at-risk cushions ({@code CFG_SLA_POLICY}), alert rules
 * ({@code CFG_ALERT_RULE}) and scalar knobs ({@code CFG_PARAM}). Reads are cheap single-table
 * selects done per request, so an edit takes effect on the next page load with no cache to bust.
 *
 * <p>Every edit is validated here (the DB CHECKs are the backstop, not the message) and logged to
 * {@code CFG_CHANGE_LOG} in the same transaction as the change.
 */
@Service
@Lazy
public class ParametersUseCase {

    static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "HIGH", "CRITICAL");
    private static final int MAX_SLA_DAYS = 365;
    private static final int RECENT_CHANGES = 20;
    private static final List<String> LADDER = List.of("critical", "high", "medium", "low");

    private final JdbcTemplate jdbc;

    public ParametersUseCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------------------ reads

    public Snapshot snapshot() {
        return new Snapshot(slaPolicy(), alertRules(), params(), recentChanges());
    }

    public List<SlaItem> slaPolicy() {
        return jdbc.query("SELECT PRIORITY_ID, PRIORITY_NAME, SLA_DAYS, AT_RISK_REMAINING_DAYS "
                        + "FROM CFG_SLA_POLICY ORDER BY SLA_DAYS ASC, PRIORITY_ID ASC",
                (rs, n) -> new SlaItem(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4)));
    }

    public List<AlertRule> alertRules() {
        return jdbc.query("SELECT RULE_CODE, IS_ENABLED, SEVERITY, MIN_COUNT, THRESHOLD, "
                        + "DEFAULT_ENABLED, DEFAULT_SEVERITY, DEFAULT_MIN_COUNT, DEFAULT_THRESHOLD, "
                        + "UPDATED_AT, UPDATED_BY FROM CFG_ALERT_RULE ORDER BY RULE_CODE",
                (rs, n) -> new AlertRule(rs.getString(1), rs.getInt(2) == 1, rs.getString(3),
                        rs.getInt(4), dbl(rs.getBigDecimal(5)), rs.getInt(6) == 1,
                        rs.getString(7), rs.getInt(8), dbl(rs.getBigDecimal(9)),
                        instant(rs.getTimestamp(10)), rs.getString(11)));
    }

    public List<Param> params() {
        return jdbc.query("SELECT PARAM_KEY, VALUE_TYPE, PARAM_VALUE, DEFAULT_VALUE, MIN_VALUE, "
                        + "MAX_VALUE, UPDATED_AT, UPDATED_BY FROM CFG_PARAM ORDER BY PARAM_KEY",
                (rs, n) -> new Param(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), dbl(rs.getBigDecimal(5)), dbl(rs.getBigDecimal(6)),
                        instant(rs.getTimestamp(7)), rs.getString(8)));
    }

    public List<Change> recentChanges() {
        return jdbc.query("SELECT CHANGED_AT, CHANGED_BY, TARGET, OLD_VALUE, NEW_VALUE "
                        + "FROM CFG_CHANGE_LOG ORDER BY ID DESC FETCH FIRST " + RECENT_CHANGES
                        + " ROWS ONLY",
                (rs, n) -> new Change(instant(rs.getTimestamp(1)), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    /** What the pages read at runtime. */
    public Map<String, AlertRule> alertRulesByCode() {
        Map<String, AlertRule> byCode = new LinkedHashMap<>();
        alertRules().forEach(r -> byCode.put(r.code(), r));
        return byCode;
    }

    public ForecastModel.Params forecastParams() {
        Map<String, String> p = paramValues();
        return new ForecastModel.Params(
                intOf(p, "forecast.trend_window_days", ForecastModel.TREND_WINDOW),
                doubleOf(p, "forecast.smoothing_alpha", ForecastModel.ALPHA),
                intOf(p, "forecast.surge_change_pct", ForecastModel.SURGE_CHANGE_PCT));
    }

    public int defaultHorizonDays() {
        return intOf(paramValues(), "forecast.default_horizon_days", 7);
    }

    public int alertSampleSize() {
        return intOf(paramValues(), "alerts.ticket_sample_size", 10);
    }

    public String brandCategoryPrefix() {
        return paramValues().getOrDefault("brand.category_prefix", "05");
    }

    private Map<String, String> paramValues() {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT PARAM_KEY, PARAM_VALUE FROM CFG_PARAM",
                rs -> {
                    values.put(rs.getString(1), rs.getString(2));
                });
        return values;
    }

    // ----------------------------------------------------------------------------- writes

    /**
     * Replaces the per-priority SLA budget and at-risk cushion. The whole ladder is validated as one
     * unit: each row on its own, then that a tighter priority never gets MORE days than a looser one.
     * The CFG_SLA_POLICY trigger bumps the config generation, so the mart is re-derived on its next
     * refresh.
     */
    @Transactional
    public void updateSlaPolicy(String actor, List<SlaUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new ConfigException("Send at least one priority.", "items");
        }
        Map<Long, SlaItem> current = new LinkedHashMap<>();
        slaPolicy().forEach(i -> current.put(i.priorityId(), i));
        Map<Long, SlaUpdate> next = new LinkedHashMap<>();
        for (SlaUpdate u : updates) {
            SlaItem item = current.get(u.priorityId());
            if (item == null) {
                throw new ConfigException("Unknown priority " + u.priorityId() + ".", "priorityId");
            }
            if (next.put(u.priorityId(), u) != null) {
                throw new ConfigException("Priority " + item.priorityName()
                        + " is listed twice.", "priorityId");
            }
            if (u.slaDays() < 1 || u.slaDays() > MAX_SLA_DAYS) {
                throw new ConfigException(item.priorityName() + ": SLA days must be 1 to "
                        + MAX_SLA_DAYS + ".", "slaDays");
            }
            if (u.atRiskRemainingDays() < 0 || u.atRiskRemainingDays() >= u.slaDays()) {
                throw new ConfigException(item.priorityName()
                        + ": the at-risk cushion must be 0 or more and less than the SLA days.",
                        "atRiskRemainingDays");
            }
        }
        // Ladder order, over the final state (edited rows win, untouched rows stay).
        Map<String, Integer> days = new HashMap<>();
        for (SlaItem item : current.values()) {
            SlaUpdate u = next.get(item.priorityId());
            days.put(item.priorityName().trim().toLowerCase(), u == null ? item.slaDays()
                    : u.slaDays());
        }
        Integer previous = null;
        String previousName = null;
        for (String name : LADDER) {
            Integer d = days.get(name);
            if (d == null) {
                continue;
            }
            if (previous != null && d < previous) {
                throw new ConfigException(capitalise(name) + " cannot have fewer SLA days than "
                        + previousName + ": a tighter priority must not get a longer budget.",
                        "slaDays");
            }
            previous = d;
            previousName = capitalise(name);
        }
        for (SlaUpdate u : next.values()) {
            SlaItem item = current.get(u.priorityId());
            if (item.slaDays() == u.slaDays()
                    && item.atRiskRemainingDays() == u.atRiskRemainingDays()) {
                continue;
            }
            jdbc.update("UPDATE CFG_SLA_POLICY SET SLA_DAYS = ?, AT_RISK_REMAINING_DAYS = ? "
                            + "WHERE PRIORITY_ID = ?",
                    u.slaDays(), u.atRiskRemainingDays(), u.priorityId());
            log(actor, "sla:" + item.priorityName(),
                    item.slaDays() + "d / cushion " + item.atRiskRemainingDays() + "d",
                    u.slaDays() + "d / cushion " + u.atRiskRemainingDays() + "d");
        }
    }

    @Transactional
    public AlertRule updateAlertRule(String actor, String code, AlertRuleUpdate update) {
        if (update == null) {
            throw new ConfigException("Send the fields to change.", "body");
        }
        jdbc.query("SELECT 1 FROM CFG_ALERT_RULE WHERE RULE_CODE = ? FOR UPDATE", rs -> {
        }, code);
        AlertRule rule = alertRulesByCode().get(code);
        if (rule == null) {
            throw new ConfigException("Unknown alert rule.", "code");
        }
        boolean enabled = update.enabled() == null ? rule.enabled() : update.enabled();
        String severity = update.severity() == null ? rule.severity() : update.severity();
        int minCount = update.minCount() == null ? rule.minCount() : update.minCount();
        Double threshold = update.threshold() == null ? rule.threshold() : update.threshold();
        if (!SEVERITIES.contains(severity)) {
            throw new ConfigException("Severity must be one of INFO, WARNING, HIGH, CRITICAL.",
                    "severity");
        }
        if (minCount < 1 || minCount > 100_000) {
            throw new ConfigException("Minimum tickets must be 1 or more.", "minCount");
        }
        if (!rule.hasThreshold()) {
            if (update.threshold() != null) {
                throw new ConfigException("This rule has no threshold.", "threshold");
            }
            threshold = null;
        } else if (threshold == null || threshold < 0 || threshold > 3650
                || Double.isNaN(threshold)) {
            throw new ConfigException("Threshold must be a number from 0 to 3650.", "threshold");
        }
        String before = describe(rule.enabled(), rule.severity(), rule.minCount(),
                rule.threshold());
        String after = describe(enabled, severity, minCount, threshold);
        if (!before.equals(after)) {
            jdbc.update("UPDATE CFG_ALERT_RULE SET IS_ENABLED = ?, SEVERITY = ?, MIN_COUNT = ?, "
                            + "THRESHOLD = ?, UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = ? "
                            + "WHERE RULE_CODE = ?",
                    enabled ? 1 : 0, severity, minCount, threshold, actor, code);
            log(actor, "alert:" + code, before, after);
        }
        return alertRulesByCode().get(code);
    }

    @Transactional
    public Param updateParam(String actor, String key, String value) {
        jdbc.query("SELECT 1 FROM CFG_PARAM WHERE PARAM_KEY = ? FOR UPDATE", rs -> {
        }, key);
        Param param = params().stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new ConfigException("Unknown parameter.", "key"));
        String clean = value == null ? "" : value.trim();
        String canonical = validate(param, clean);
        if (!canonical.equals(param.value())) {
            jdbc.update("UPDATE CFG_PARAM SET PARAM_VALUE = ?, UPDATED_AT = SYSTIMESTAMP, "
                    + "UPDATED_BY = ? WHERE PARAM_KEY = ?", canonical, actor, key);
            log(actor, "param:" + key, param.value(), canonical);
        }
        return params().stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }

    private static String validate(Param param, String value) {
        if ("brand.category_prefix".equals(param.key())) {
            if (!value.matches("\\d{1,3}")) {
                throw new ConfigException("The category code prefix is 1 to 3 digits.", "value");
            }
            return value;
        }
        if (value.length() > 32) {
            throw new ConfigException("Value is too long.", "value");
        }
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("Enter a number.", "value");
        }
        if ("INT".equals(param.type()) && number.stripTrailingZeros().scale() > 0) {
            throw new ConfigException("Enter a whole number.", "value");
        }
        if (param.min() != null && number.doubleValue() < param.min()
                || param.max() != null && number.doubleValue() > param.max()) {
            throw new ConfigException("Allowed range is " + trim(param.min()) + " to "
                    + trim(param.max()) + ".", "value");
        }
        return "INT".equals(param.type()) ? number.toBigInteger().toString()
                : number.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------------------- helpers

    private void log(String actor, String target, String oldValue, String newValue) {
        jdbc.update("INSERT INTO CFG_CHANGE_LOG (CHANGED_BY, TARGET, OLD_VALUE, NEW_VALUE) "
                + "VALUES (?, ?, ?, ?)", actor, target, oldValue, newValue);
    }

    private static String describe(boolean enabled, String severity, int minCount,
            Double threshold) {
        return (enabled ? "on" : "off") + " " + severity + " min " + minCount
                + (threshold == null ? "" : " thr " + trim(threshold));
    }

    private static String trim(Double d) {
        if (d == null) {
            return "-";
        }
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Double dbl(BigDecimal b) {
        return b == null ? null : b.doubleValue();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static int intOf(Map<String, String> m, String key, int fallback) {
        try {
            return m.containsKey(key) ? Integer.parseInt(m.get(key)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleOf(Map<String, String> m, String key, double fallback) {
        try {
            return m.containsKey(key) ? Double.parseDouble(m.get(key)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
