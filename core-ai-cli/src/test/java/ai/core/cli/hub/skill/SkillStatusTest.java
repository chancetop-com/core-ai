package ai.core.cli.hub.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillStatusTest {
    private static final String LOCAL_DIGEST = "local";
    private static final String MARKER_DIGEST = "marker";
    private static final String SERVER_DIGEST = "server";

    private SkillHubMarker.Marker marker(String digest) {
        return new SkillHubMarker.Marker("stephen/code-review", "id", digest, "server", "ts");
    }

    @Test
    void noMarkerMeansLocal() {
        assertEquals(SkillStatus.LOCAL, SkillStatus.of(null, LOCAL_DIGEST, SERVER_DIGEST, true));
    }

    @Test
    void markerWithoutDigestIsUnverified() {
        assertEquals(SkillStatus.UNVERIFIED, SkillStatus.of(marker(null), LOCAL_DIGEST, SERVER_DIGEST, true));
    }

    @Test
    void localDriftIsModifiedRegardlessOfServer() {
        assertEquals(SkillStatus.MODIFIED, SkillStatus.of(marker(MARKER_DIGEST), "edited", SERVER_DIGEST, true));
    }

    @Test
    void matchingDigestsAreUpToDate() {
        assertEquals(SkillStatus.UP_TO_DATE, SkillStatus.of(marker(SERVER_DIGEST), SERVER_DIGEST, SERVER_DIGEST, true));
    }

    @Test
    void serverChangeMarksOutdated() {
        assertEquals(SkillStatus.OUTDATED, SkillStatus.of(marker(MARKER_DIGEST), MARKER_DIGEST, SERVER_DIGEST, true));
    }

    @Test
    void offlineServerShowsUnmodifiedInsteadOfOutdated() {
        assertEquals("unmodified", SkillStatus.of(marker(MARKER_DIGEST), MARKER_DIGEST, SERVER_DIGEST, false));
    }

    @Test
    void missingServerDigestIsUnverified() {
        assertEquals(SkillStatus.UNVERIFIED, SkillStatus.of(marker(MARKER_DIGEST), MARKER_DIGEST, null, true));
    }
}
