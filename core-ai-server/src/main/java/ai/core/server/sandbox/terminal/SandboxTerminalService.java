package ai.core.server.sandbox.terminal;

import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.session.SessionActivityRegistry;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Single authorization/resolution/ticket-minting path for the interactive
 * sandbox terminal: gate check, session ownership check, fresh sandbox-runtime
 * address resolution on every call, and signing the one-shot ticket the
 * frontend uses to dial the terminal gateway directly. Also owns the
 * activity-touch throttle shared by the ticket and activity endpoints (both
 * keep the session/sandbox alive).
 * <p>
 * Deliberately does NOT cache resolved addresses (v1 had a 60s cache; removed
 * in v2): a ticket is minted once per connection attempt, and a stale cached
 * pod address would poison every ticket minted within the cache window with
 * no feedback path -- the gateway would simply dial a dead pod. Resolution
 * itself (via {@link SandboxTerminalRuntimeResolver}) is already cheap and
 * lifecycle-safe (attach-only, no provider round trip on the owner pod).
 * <p>
 * Depends on narrow functional seams rather than concrete SessionRegistry /
 * SandboxService types so it can be unit-tested without Mongo or Redis;
 * production wiring adapts {@code sessionRegistry::requireAccessible} and
 * {@code terminalRuntimeResolver::resolveTerminalRuntime}.
 * <p>
 * The gate is open only when {@link #enabled} is set AND both {@link #ticketSecret}
 * and {@link #gatewayUrl} are configured -- an empty secret or gateway URL makes
 * minting a ticket either insecure (unsigned) or useless (nowhere to send it),
 * so both disable the feature exactly like {@link #enabled} being false.
 * <p>
 * Exception mapping: gate disabled -&gt; {@link NotFoundException} with errorCode
 * {@code TERMINAL_DISABLED}; REPLACED -&gt; {@link ConflictException} (409) with
 * errorCode {@code SANDBOX_REPLACED}; MISSING -&gt; {@link NotFoundException} with
 * errorCode {@code SANDBOX_EXPIRED} (core-ng has no Gone-style exception, so the
 * frontend distinguishes replaced/expired by errorCode, not status).
 *
 * @author xander
 */
public class SandboxTerminalService {
    private static final long ACTIVITY_THROTTLE_MILLIS = 60_000L;
    private static final long TICKET_TTL_SECONDS = 30L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String randomNonce() {
        var bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private final SessionAccessChecker accessChecker;
    private final TerminalRuntimeResolver resolver;
    private final SessionActivityRegistry activityRegistry;
    private final Consumer<String> localTouch;

    private final Map<String, Long> lastTouch = new ConcurrentHashMap<>();

    public boolean enabled;
    /** HMAC secret used to sign minted tickets; an empty (default) secret disables the gate regardless of {@link #enabled}. */
    public byte[] ticketSecret = new byte[0];
    /** Public base URL of the terminal gateway, handed back to the frontend alongside every minted ticket. */
    public String gatewayUrl = "";

    public SandboxTerminalService(SessionAccessChecker accessChecker, TerminalRuntimeResolver resolver,
                                  SessionActivityRegistry activityRegistry, Consumer<String> localTouch) {
        this.accessChecker = accessChecker;
        this.resolver = resolver;
        this.activityRegistry = activityRegistry;
        this.localTouch = localTouch;
    }

    public SandboxTerminalRuntimeResolver.TerminalRuntime authorize(String sessionId, String sandboxId, String userId) {
        requireAccessible(sessionId, userId);

        var runtime = resolver.resolve(sessionId, sandboxId, userId);
        if (runtime.status() == SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.REPLACED) {
            throw new ConflictException("sandbox has been replaced, sandboxId=" + sandboxId, "SANDBOX_REPLACED");
        }
        if (runtime.status() == SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.MISSING) {
            throw new NotFoundException("sandbox terminal runtime is no longer available, sandboxId=" + sandboxId, "SANDBOX_EXPIRED");
        }
        return runtime;
    }

    /** Gate + session-ownership check only, no sandbox resolution; used by the activity heartbeat, which has no sandbox id. */
    public void requireAccessible(String sessionId, String userId) {
        if (!gateOpen()) throw new NotFoundException("sandbox terminal is disabled", "TERMINAL_DISABLED");
        accessChecker.requireAccessible(sessionId, userId);
    }

    /** Authorizes, then signs a fresh one-shot ticket bound to the resolved runtime address and the caller's client id. */
    public String mintTicket(String sessionId, String sandboxId, String userId, String clientId) {
        var runtime = authorize(sessionId, sandboxId, userId);
        long issuedAt = System.currentTimeMillis() / 1000L;
        var ticket = new TerminalTicket(sessionId, sandboxId, clientId, runtime.ip(), runtime.port(),
                issuedAt, issuedAt + TICKET_TTL_SECONDS, randomNonce());
        return TerminalTicketCodec.mint(ticket, ticketSecret);
    }

    public void recordActivity(String sessionId) {
        var now = System.currentTimeMillis();
        var previous = lastTouch.get(sessionId);
        if (previous != null && now - previous < ACTIVITY_THROTTLE_MILLIS) return;
        lastTouch.put(sessionId, now);
        activityRegistry.touch(sessionId);
        localTouch.accept(sessionId);
    }

    private boolean gateOpen() {
        return enabled && ticketSecret.length > 0 && gatewayUrl != null && !gatewayUrl.isBlank();
    }

    public interface SessionAccessChecker {
        void requireAccessible(String sessionId, String userId);
    }

    public interface TerminalRuntimeResolver {
        SandboxTerminalRuntimeResolver.TerminalRuntime resolve(String sessionId, String sandboxId, String userId);
    }
}
