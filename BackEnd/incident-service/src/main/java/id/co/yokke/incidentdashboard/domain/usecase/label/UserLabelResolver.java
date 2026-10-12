package id.co.yokke.incidentdashboard.domain.usecase.label;

/**
 * Resolves a Redmine user id to the label the source report shows for it.
 *
 * <p>The source resolves three report columns from user identity by three DIFFERENT rules —
 * one {@code resolve(Long)} cannot express them, because whichever rule it implemented would
 * make the other two wrong for most rows. Verified against the delivered workbook:
 *
 * <ul>
 *   <li>{@code AUTHOR} — {@code email_addresses.address}, with NO fallback. NULL when the
 *       user has no email row: this is the ONE exception to the never-null rule below, because
 *       parity with the delivered workbook outranks it.
 *   <li>{@code ASSIGNED_TO} — {@code address}, else {@code users.lastname}.
 *   <li>{@code DEVELOPER_ASSIGNED} — {@code users.firstname}.
 * </ul>
 *
 * <p>{@link #assigneeLabel} and {@link #developerLabel} follow the never-null rule: a
 * {@code null} user id resolves to {@link EnumLabelResolver#UNCLASSIFIED}, an id this
 * resolver's snapshot has no row for resolves to {@code "Unknown (<id>)"}.
 */
public interface UserLabelResolver {

    /**
     * The AUTHOR column rule: {@code email_addresses.address}, no fallback.
     *
     * @return the user's address, or {@code null} when the user has no email row (or no user
     *     id was given at all) — deliberately, to match the source report's own NULLs
     */
    String authorLabel(Long userId);

    /** The ASSIGNED_TO column rule: {@code address} else {@code users.lastname}. */
    String assigneeLabel(Long userId);

    /** The DEVELOPER_ASSIGNED column rule: {@code users.firstname}. */
    String developerLabel(Long userId);

    /**
     * Reloads the cached snapshot the three rules above are served from.
     *
     * <p>On the interface for the same reason {@link EnumLabelResolver#refresh()} is: the ETL
     * (Task 11) refreshes both label snapshots once per RUN — not once per row, which would let
     * two rows of one chunk disagree, and not never, which would serve a snapshot taken when
     * the bean was first constructed for the rest of the process's life.
     */
    void refresh();
}
