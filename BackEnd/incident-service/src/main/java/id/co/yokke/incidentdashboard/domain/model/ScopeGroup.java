package id.co.yokke.incidentdashboard.domain.model;

/**
 * Which family of trackers a request is about. The wire token IS the enum name, and it is also
 * the value stored in {@code CFG_TRACKER_SCOPE.SCOPE_GROUP} (V10) -- the mapping from tracker to
 * group lives in config, never in Java.
 */
public enum ScopeGroup {
    INCIDENT,
    PROBLEM,
    SERVICE_REQUEST
}
