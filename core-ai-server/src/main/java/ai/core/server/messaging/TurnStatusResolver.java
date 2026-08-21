package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;

/**
 * Combines the cross-pod turn liveness (authoritative for "is the turn alive") with the
 * pod-local SSE channel view (carries the error nuance) into the session status answer.
 *
 * @author xander
 */
public final class TurnStatusResolver {
    public static SessionStatus resolve(TurnStateRegistry.TurnLiveness liveness, SessionStatus localStatus) {
        if (liveness == TurnStateRegistry.TurnLiveness.RUNNING) return SessionStatus.RUNNING;
        if (liveness == TurnStateRegistry.TurnLiveness.UNKNOWN) return localStatus;
        // Registry says the turn is over. A locally buffered RUNNING at this point means
        // the terminal event was lost in transit — report finished so clients re-sync.
        return localStatus == SessionStatus.ERROR ? SessionStatus.ERROR : SessionStatus.IDLE;
    }

    private TurnStatusResolver() {
    }
}
