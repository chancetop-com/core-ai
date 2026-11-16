package ai.core.server.messaging;

/**
 * @author stephen
 */
public interface TurnStateStore {
    void markRunning(String sessionId, String userId, String ownerPod);

    void clear(String sessionId, String userId, String ownerPod);

    void renew(String sessionId, String userId);

    TurnStateRegistry.TurnLiveness liveness(String sessionId);

    int inFlightTurns(String userId);
}
