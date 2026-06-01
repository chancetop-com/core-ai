package ai.core.sandbox;

/**
 * @author stephen
 */
public interface SandboxProvider {

    Sandbox acquire(SandboxConfig config, String sessionId, String userId);

    void release(Sandbox sandbox);

    default void renew(Sandbox sandbox, int timeoutSeconds) {
    }

    SandboxStatus getStatus(Sandbox sandbox);
}
