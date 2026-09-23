package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.sandboxhub.SandboxHubBinder;
import ai.core.server.sandboxhub.SessionTokenService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * The hub identity of a session sandbox: hands the sandbox runtime the session token it replays on
 * every hub call a script makes, and answers whether a sandbox still is the one that token names.
 * <p>
 * Wraps {@link SandboxHubBinder} with the address scripts must reach and the per-session identity the
 * binder needs when a token has to be re-minted, so callers only supply what changes per sandbox.
 * Every operation is best effort by design — a failure costs the hub, never the session.
 *
 * @author xander
 */
class SandboxHubBinding {
    /** Keeps the token inside the sandbox lifetime: a token that outlives its sandbox is useless. */
    static int ttlSeconds(SandboxConfig defaultConfig) {
        var ttl = defaultConfig != null && defaultConfig.timeoutSeconds != null ? defaultConfig.timeoutSeconds : 0;
        return ttl > 0 ? ttl : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;
    }

    private final SandboxHubBinder binder;
    private final String serverUrl;
    private final IntSupplier ttlSeconds;
    private final Map<String, String> userIds = new ConcurrentHashMap<>();
    private final Map<String, String> agentNames = new ConcurrentHashMap<>();

    SandboxHubBinding(SessionTokenService sessionTokenService, String serverUrlFromSandbox, IntSupplier ttlSeconds) {
        this.binder = new SandboxHubBinder(sessionTokenService);
        this.serverUrl = serverUrlFromSandbox;
        this.ttlSeconds = ttlSeconds;
    }

    /** {@code agentName} is informational only — it reaches scripts as {@code CORE_AI_AGENT_NAME}. */
    void rememberAgent(String sessionId, String agentName) {
        if (sessionId != null && agentName != null && !agentName.isBlank()) {
            agentNames.put(sessionId, agentName);
        }
    }

    void rememberUser(String sessionId, String userId) {
        if (sessionId != null && userId != null && !userId.isBlank()) {
            userIds.put(sessionId, userId);
        }
    }

    void forget(String sessionId) {
        agentNames.remove(sessionId);
        userIds.remove(sessionId);
    }

    /** True while the token's sandbox id still names the sandbox the session currently holds. */
    boolean isBound(Sandbox sandbox, String sandboxId) {
        return sandbox != null && isBound(sandbox.getId(), sandboxId);
    }

    /**
     * Same question answered from a binding this pod read elsewhere (the session's sandbox lives on
     * another replica): the stored id is the identity the owning pod would compare against.
     */
    boolean isBound(String boundSandboxId, String sandboxId) {
        return boundSandboxId != null && !boundSandboxId.isBlank() && boundSandboxId.equals(sandboxId);
    }

    void bind(Sandbox sandbox, String sessionId, String userId) {
        binder.bind(sandbox, serverUrl, sessionId, userId, agentNames.get(sessionId), ttlSeconds.getAsInt());
    }

    void rebindIfNeeded(Sandbox sandbox, String sessionId) {
        binder.rebindIfNeeded(sandbox, serverUrl, sessionId, agentNames.get(sessionId), ttlSeconds.getAsInt());
    }

    /** Re-binds a sandbox whose runtime came back without the identity (restarted process, replaced pod). */
    void heal(Sandbox sandbox, String sessionId) {
        var userId = userIds.get(sessionId);
        if (userId == null) return;
        binder.rebindIfRuntimeLost(sandbox, serverUrl, sessionId, userId, agentNames.get(sessionId), ttlSeconds.getAsInt());
    }

    void unbind(Sandbox sandbox, String sessionId) {
        binder.unbind(sandbox, sessionId);
    }
}
