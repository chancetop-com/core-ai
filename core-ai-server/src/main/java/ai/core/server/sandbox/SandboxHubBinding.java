package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.sandboxhub.SandboxHubBinder;
import ai.core.server.sandboxhub.SessionTokenService;

import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The hub identity of a session sandbox: hands the sandbox runtime the session token it replays on
 * every hub call a script makes, and answers whether a sandbox still is the one that token names.
 * <p>
 * Wraps {@link SandboxHubBinder} with the address scripts must reach, so callers only supply what
 * changes per sandbox. Every operation is best effort by design — a failure costs the hub, never
 * the session.
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
    private final Function<String, String> agentNameOf;

    SandboxHubBinding(SessionTokenService sessionTokenService, String serverUrlFromSandbox,
                      IntSupplier ttlSeconds, Function<String, String> agentNameOf) {
        this.binder = new SandboxHubBinder(sessionTokenService);
        this.serverUrl = serverUrlFromSandbox;
        this.ttlSeconds = ttlSeconds;
        this.agentNameOf = agentNameOf;
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
        binder.bind(sandbox, serverUrl, sessionId, userId, agentNameOf.apply(sessionId), ttlSeconds.getAsInt());
    }

    void rebindIfNeeded(Sandbox sandbox, String sessionId) {
        binder.rebindIfNeeded(sandbox, serverUrl, sessionId, agentNameOf.apply(sessionId), ttlSeconds.getAsInt());
    }

    void unbind(Sandbox sandbox, String sessionId) {
        binder.unbind(sandbox, sessionId);
    }
}
