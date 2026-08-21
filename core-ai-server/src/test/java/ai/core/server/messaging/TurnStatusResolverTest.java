package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurnStatusResolverTest {
    @Test
    void runningLivenessIsAuthoritativeRegardlessOfLocalView() {
        assertEquals(SessionStatus.RUNNING, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.RUNNING, SessionStatus.IDLE));
        assertEquals(SessionStatus.RUNNING, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.RUNNING, SessionStatus.RUNNING));
        assertEquals(SessionStatus.RUNNING, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.RUNNING, SessionStatus.ERROR));
    }

    @Test
    void notRunningOverridesStaleLocalRunningView() {
        // Local buffer may still show RUNNING when the terminal event was lost in transit;
        // the registry says the turn is over, so report it as finished.
        assertEquals(SessionStatus.IDLE, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.NOT_RUNNING, SessionStatus.RUNNING));
        assertEquals(SessionStatus.IDLE, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.NOT_RUNNING, SessionStatus.IDLE));
    }

    @Test
    void notRunningKeepsLocalErrorNuance() {
        assertEquals(SessionStatus.ERROR, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.NOT_RUNNING, SessionStatus.ERROR));
    }

    @Test
    void unknownLivenessDegradesToLocalView() {
        // Redis unavailable — fall back to the pod-local answer instead of guessing.
        assertEquals(SessionStatus.RUNNING, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.UNKNOWN, SessionStatus.RUNNING));
        assertEquals(SessionStatus.IDLE, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.UNKNOWN, SessionStatus.IDLE));
        assertEquals(SessionStatus.ERROR, TurnStatusResolver.resolve(TurnStateRegistry.TurnLiveness.UNKNOWN, SessionStatus.ERROR));
    }
}
