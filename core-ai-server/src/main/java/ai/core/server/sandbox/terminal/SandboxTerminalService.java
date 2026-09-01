package ai.core.server.sandbox.terminal;

import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.session.SessionActivityRegistry;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Single authorization/resolution path for the interactive sandbox terminal:
 * gate check, session ownership check, then sandbox-runtime address
 * resolution with a short-lived cache. Also owns the activity-touch throttle
 * shared by every terminal endpoint (create/input/resize all keep the
 * session/sandbox alive).
 * <p>
 * Depends on narrow functional seams rather than concrete SessionRegistry /
 * SandboxService types so it can be unit-tested without Mongo or Redis;
 * production wiring adapts {@code sessionRegistry::requireAccessible} and
 * {@code terminalRuntimeResolver::resolveTerminalRuntime}.
 * <p>
 * Exception mapping (see {@link SandboxTerminalWebServiceImpl} for the
 * runtime-client exception mapping): gate disabled -&gt; {@link NotFoundException}
 * with errorCode {@code TERMINAL_DISABLED}; REPLACED -&gt; {@link ConflictException}
 * (409) with errorCode {@code SANDBOX_REPLACED}; MISSING -&gt; {@link NotFoundException}
 * with errorCode {@code SANDBOX_EXPIRED} (core-ng has no Gone-style exception, so
 * the frontend distinguishes replaced/expired by errorCode, not status).
 *
 * @author xander
 */
public class SandboxTerminalService {
    private static final long ADDRESS_CACHE_TTL_MILLIS = 60_000L;
    private static final long ACTIVITY_THROTTLE_MILLIS = 60_000L;

    private final SessionAccessChecker accessChecker;
    private final TerminalRuntimeResolver resolver;
    private final SessionActivityRegistry activityRegistry;
    private final Consumer<String> localTouch;

    private final Map<String, CachedAddress> addressCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTouch = new ConcurrentHashMap<>();

    public boolean enabled;

    public SandboxTerminalService(SessionAccessChecker accessChecker, TerminalRuntimeResolver resolver,
                                  SessionActivityRegistry activityRegistry, Consumer<String> localTouch) {
        this.accessChecker = accessChecker;
        this.resolver = resolver;
        this.activityRegistry = activityRegistry;
        this.localTouch = localTouch;
    }

    public SandboxTerminalClient authorize(String sessionId, String sandboxId, String userId) {
        if (!enabled) throw new NotFoundException("sandbox terminal is disabled", "TERMINAL_DISABLED");
        accessChecker.requireAccessible(sessionId, userId);

        var cached = addressCache.get(sandboxId);
        if (cached != null && System.currentTimeMillis() - cached.resolvedAt() < ADDRESS_CACHE_TTL_MILLIS) {
            return new SandboxTerminalClient(cached.ip(), cached.port());
        }

        var runtime = resolver.resolve(sessionId, sandboxId, userId);
        if (runtime.status() == SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.REPLACED) {
            throw new ConflictException("sandbox has been replaced, sandboxId=" + sandboxId, "SANDBOX_REPLACED");
        }
        if (runtime.status() == SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.MISSING) {
            throw new NotFoundException("sandbox terminal runtime is no longer available, sandboxId=" + sandboxId, "SANDBOX_EXPIRED");
        }
        addressCache.put(sandboxId, new CachedAddress(runtime.ip(), runtime.port(), System.currentTimeMillis()));
        return new SandboxTerminalClient(runtime.ip(), runtime.port());
    }

    /** Called by REST/SSE callers after a proxy call fails with TerminalRuntimeUnavailableException. */
    public void invalidateAddress(String sandboxId) {
        addressCache.remove(sandboxId);
    }

    public void recordActivity(String sessionId) {
        var now = System.currentTimeMillis();
        var previous = lastTouch.get(sessionId);
        if (previous != null && now - previous < ACTIVITY_THROTTLE_MILLIS) return;
        lastTouch.put(sessionId, now);
        activityRegistry.touch(sessionId);
        localTouch.accept(sessionId);
    }

    public interface SessionAccessChecker {
        void requireAccessible(String sessionId, String userId);
    }

    public interface TerminalRuntimeResolver {
        SandboxTerminalRuntimeResolver.TerminalRuntime resolve(String sessionId, String sandboxId, String userId);
    }

    private record CachedAddress(String ip, int port, long resolvedAt) {
    }
}
