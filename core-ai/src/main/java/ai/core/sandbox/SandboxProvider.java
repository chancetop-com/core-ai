package ai.core.sandbox;

import java.util.Optional;

/**
 * @author stephen
 */
public interface SandboxProvider {

    Sandbox acquire(SandboxConfig config, String sessionId, String userId);

    default Optional<Sandbox> attach(String sandboxId, SandboxConfig config, String sessionId, String userId) {
        return Optional.empty();
    }

    void release(Sandbox sandbox);

    default void renew(Sandbox sandbox, int timeoutSeconds) {
    }

    SandboxStatus getStatus(Sandbox sandbox);
}
