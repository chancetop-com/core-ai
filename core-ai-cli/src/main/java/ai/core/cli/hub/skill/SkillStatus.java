package ai.core.cli.hub.skill;

/**
 * Status derivation for installed skills, shared by {@code skill list} and
 * {@code skill update}: the marker digest is what was pulled, the local digest is
 * what is on disk now, the server digest is what the hub catalog reports.
 *
 * @author stephen
 */
public final class SkillStatus {
    public static final String UP_TO_DATE = "up-to-date";
    public static final String OUTDATED = "outdated";
    public static final String MODIFIED = "modified";
    public static final String LOCAL = "local";
    public static final String UNVERIFIED = "unverified";

    public static String of(SkillHubMarker.Marker marker, String localDigest, String serverDigest, boolean serverAvailable) {
        if (marker == null) return LOCAL;
        if (marker.digest() == null) return UNVERIFIED;
        if (localDigest == null || !localDigest.equals(marker.digest())) return MODIFIED;
        if (!serverAvailable) return "unmodified";
        if (serverDigest == null) return UNVERIFIED;
        return serverDigest.equals(marker.digest()) ? UP_TO_DATE : OUTDATED;
    }

    private SkillStatus() {
    }
}
