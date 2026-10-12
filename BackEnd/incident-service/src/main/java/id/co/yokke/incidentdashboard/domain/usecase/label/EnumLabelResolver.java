package id.co.yokke.incidentdashboard.domain.usecase.label;

/**
 * Resolves a Redmine custom-field enum value id to its display label.
 *
 * <p>Never returns {@code null} or an empty string: an aggregate needs to be able to count
 * every value, including the ones that are absent or unresolvable. Those are two DIFFERENT
 * facts and must stay distinguishable — {@link #UNCLASSIFIED} means "no value was set",
 * {@code "Unknown (<id>)"} means "a value was set but this snapshot cannot resolve it".
 *
 * <p>Implementations live in {@code infrastructure.datasource.label} and are selected via
 * {@code incident.label.enum-resolver} ({@code local}, the default, or {@code mirror}) so that
 * streaming the real {@code custom_field_enumerations} table is a config swap, not a rewrite.
 */
public interface EnumLabelResolver {

    /** The label for a custom field the report never set a value for. */
    String UNCLASSIFIED = "Unclassified";

    /**
     * Resolves {@code enumValueId} for {@code customFieldId} to a display label.
     *
     * @param customFieldId the Redmine custom field id the value belongs to
     * @param enumValueId   the enum value id, or {@code null} if the field was never set
     * @return the resolved label, {@link #UNCLASSIFIED} when {@code enumValueId} is
     *     {@code null}, or {@code "Unknown (<id>)"} when the id cannot be resolved
     */
    String resolve(long customFieldId, Long enumValueId);

    /**
     * Reloads the cached snapshot this resolver serves {@link #resolve} from.
     *
     * <p>The snapshot is refreshed once per ETL run, not once per row — callers must not rely
     * on {@link #resolve} to pick up changes made after the resolver was constructed without
     * calling this first.
     */
    void refresh();
}
