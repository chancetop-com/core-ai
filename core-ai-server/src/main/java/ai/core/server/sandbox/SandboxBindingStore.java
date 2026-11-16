package ai.core.server.sandbox;

/**
 * Sandbox-to-session binding shared across pods, so a session rebuilt on another pod can reattach
 * to the sandbox its previous owner created.
 *
 * @author stephen
 */
public interface SandboxBindingStore {
    String getBinding(String sessionId);

    void saveBinding(String sessionId, String sandboxId);

    void deleteBinding(String sessionId);

    void deleteBindingStrict(String sessionId);
}
