package ai.core.server.sandboxhub;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of the sandbox hub binding: mints the session token and hands it to the sandbox
 * runtime, which replays it on every hub call a script makes. Owns no sandbox lifecycle — it is
 * called by {@code SandboxService} at the points where the session's sandbox becomes ready, is
 * renewed, or is released.
 * <p>
 * Binding is best effort by design: a failure costs the hub, never the session. A runtime too old
 * to know {@code /bind} is reported once and then ignored, so a mixed fleet keeps working.
 *
 * @author xander
 */
public class SandboxHubBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxHubBinder.class);

    /** Kept below the sandbox TTL: the token must never outlive the sandbox it identifies. */
    private static final int DEFAULT_TTL_SECONDS = 3600;
    private static final int MIN_TTL_SECONDS = 120;

    private final SessionTokenService sessionTokenService;
    private volatile boolean unsupportedRuntimeLogged;
    private volatile boolean unsupportedRuntime;

    public SandboxHubBinder(SessionTokenService sessionTokenService) {
        this.sessionTokenService = sessionTokenService;
    }

    public boolean enabled() {
        return sessionTokenService != null && sessionTokenService.enabled();
    }

    /** Mints a fresh token for the sandbox and binds it into the runtime; never throws. */
    public void bind(Sandbox sandbox, String serverUrl, String sessionId, String userId, String agentName, int ttlSeconds) {
        if (sandbox == null || serverUrl == null || !enabled() || serverUrl.isBlank() || unsupportedRuntime) {
            return;
        }
        try {
            var minted = sessionTokenService.mint(sessionId, userId, sandbox.getId(), normalizeTtl(ttlSeconds));
            sandbox.bind(new SandboxBinding(serverUrl, minted.token(), sessionId, agentName, minted.expiresAt()));
            LOGGER.info("bound sandbox hub: sessionId={}, sandboxId={}, expiresAt={}", sessionId, sandbox.getId(), minted.expiresAt());
        } catch (UnsupportedOperationException e) {
            logUnsupportedRuntime();
        } catch (Exception e) {
            LOGGER.warn("failed to bind sandbox hub: sessionId={}, sandboxId={}", sessionId, sandbox.getId(), e);
        }
    }

    /**
     * Re-binds only when the runtime reports it lost the identity it was handed — a runtime that
     * restarted keeps its sandbox alive but comes back unbound, and every script call through it would
     * answer 503 until the session renews its token. A silent runtime is left alone: an unreachable
     * sandbox is not one a bind could fix.
     */
    public void rebindIfRuntimeLost(Sandbox sandbox, String serverUrl, String sessionId, String userId, String agentName, int ttlSeconds) {
        if (sandbox == null || serverUrl == null || !enabled() || serverUrl.isBlank() || unsupportedRuntime) {
            return;
        }
        try {
            if (!Boolean.FALSE.equals(sandbox.hubBound())) {
                return;
            }
            LOGGER.info("sandbox runtime lost its hub binding, rebinding: sessionId={}, sandboxId={}", sessionId, sandbox.getId());
            bind(sandbox, serverUrl, sessionId, userId, agentName, ttlSeconds);
        } catch (Exception e) {
            LOGGER.warn("failed to probe sandbox hub binding: sessionId={}, sandboxId={}", sessionId, sandbox.getId(), e);
        }
    }

    /**
     * Re-binds only once the bound token has burned half of its lifetime, or when the sandbox was
     * replaced under the session (self-heal); never throws.
     */
    public void rebindIfNeeded(Sandbox sandbox, String serverUrl, String sessionId, String agentName, int ttlSeconds) {
        if (sandbox == null || serverUrl == null || !enabled() || serverUrl.isBlank() || unsupportedRuntime) {
            return;
        }
        try {
            var minted = sessionTokenService.renewIfNeeded(sessionId, sandbox.getId(), normalizeTtl(ttlSeconds));
            if (minted == null) {
                return;
            }
            sandbox.bind(new SandboxBinding(serverUrl, minted.token(), sessionId, agentName, minted.expiresAt()));
            LOGGER.info("renewed sandbox hub binding: sessionId={}, sandboxId={}, expiresAt={}", sessionId, sandbox.getId(), minted.expiresAt());
        } catch (UnsupportedOperationException e) {
            logUnsupportedRuntime();
        } catch (Exception e) {
            LOGGER.warn("failed to renew sandbox hub binding: sessionId={}, sandboxId={}", sessionId, sandbox.getId(), e);
        }
    }

    /** Forgets the session's token and clears it from the runtime; never throws. */
    public void unbind(Sandbox sandbox, String sessionId) {
        if (sessionTokenService != null) {
            sessionTokenService.unbind(sessionId);
        }
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.unbind();
        } catch (Exception e) {
            LOGGER.debug("failed to clear sandbox hub binding: sessionId={}", sessionId, e);
        }
    }

    private void logUnsupportedRuntime() {
        unsupportedRuntime = true;
        if (!unsupportedRuntimeLogged) {
            unsupportedRuntimeLogged = true;
            LOGGER.warn("sandbox runtime does not support hub binding (/bind); scripts cannot use the session capabilities on this runtime");
        }
    }

    private int normalizeTtl(int ttlSeconds) {
        return ttlSeconds >= MIN_TTL_SECONDS ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }
}
