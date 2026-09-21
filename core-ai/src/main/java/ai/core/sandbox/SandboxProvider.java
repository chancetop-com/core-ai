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

    /**
     * Extends the provider-side lifetime of an acquired sandbox. The config is what the sandbox was
     * acquired with, so the provider can resolve the same lifetime it wrote at acquisition — never a
     * shorter one, which would retire a sandbox that is still in use.
     */
    default void renew(Sandbox sandbox, SandboxConfig config) {
    }

    SandboxStatus getStatus(Sandbox sandbox);
}
