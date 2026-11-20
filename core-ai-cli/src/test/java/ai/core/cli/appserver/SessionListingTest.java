package ai.core.cli.appserver;

import ai.core.session.SessionPersistence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * @author stephen
 */
class SessionListingTest {
    private static SessionPersistence.SessionInfo session(String id, long savedAt) {
        return new SessionPersistence.SessionInfo(id, Instant.ofEpochMilli(savedAt));
    }

    private static List<String> ids(List<SessionPersistence.SessionInfo> infos) {
        return infos.stream().map(SessionPersistence.SessionInfo::id).toList();
    }

    @Test
    void runningTurnLeadsTheListThoughItsSnapshotIsNotSavedYet() {
        var listed = List.of(session("saved-last", 3_000), session("older", 1_000), session("running", 2_000));
        var ordered = SessionListing.runningTurnsFirst(listed, Map.of("running", 9_000L));
        assertEquals(List.of("running", "saved-last", "older"), ids(ordered));
    }

    @Test
    void newestRunningTurnSortsAboveOlderOnes() {
        var listed = List.of(session("first", 3_000), session("second", 2_000), session("old-turn", 1_000));
        var ordered = SessionListing.runningTurnsFirst(listed,
                Map.of("old-turn", 4_000L, "second", 9_000L));
        assertEquals(List.of("second", "old-turn", "first"), ids(ordered));
    }

    @Test
    void savedOrderIsKeptWhenNothingRuns() {
        var listed = List.of(session("a", 3_000), session("b", 2_000), session("c", 1_000));
        assertSame(listed, SessionListing.runningTurnsFirst(listed, Map.of()));
    }
}
