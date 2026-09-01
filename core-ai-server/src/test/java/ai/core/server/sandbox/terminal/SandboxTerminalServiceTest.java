package ai.core.server.sandbox.terminal;

import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.session.SessionActivityRegistry;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises SandboxTerminalService's authorization/resolution/ticket-minting
 * path via the narrow functional seams (SessionAccessChecker/TerminalRuntimeResolver)
 * instead of real Mongo/Redis collaborators.
 *
 * @author xander
 */
class SandboxTerminalServiceTest {
    private static final byte[] SECRET = "test-ticket-secret".getBytes(StandardCharsets.UTF_8);

    private AtomicInteger resolveCalls;
    private AtomicInteger touchCalls;
    private AtomicInteger localTouchCalls;
    private SandboxTerminalRuntimeResolver.TerminalRuntimeStatus nextStatus;
    private SessionActivityRegistry activityRegistry;
    private SandboxTerminalService service;

    @BeforeEach
    void setUp() {
        resolveCalls = new AtomicInteger();
        touchCalls = new AtomicInteger();
        localTouchCalls = new AtomicInteger();
        nextStatus = SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.CURRENT;
        activityRegistry = new SessionActivityRegistry(null) {
            @Override
            public void touch(String sessionId) {
                touchCalls.incrementAndGet();
            }
        };
        service = newService((sessionId, userId) -> { });
    }

    private SandboxTerminalService newService(SandboxTerminalService.SessionAccessChecker checker) {
        SandboxTerminalService.TerminalRuntimeResolver resolver = (sessionId, sandboxId, userId) -> {
            resolveCalls.incrementAndGet();
            return new SandboxTerminalRuntimeResolver.TerminalRuntime(nextStatus, "10.0.0.5", 8123);
        };
        var built = new SandboxTerminalService(checker, resolver, activityRegistry, sessionId -> localTouchCalls.incrementAndGet());
        built.enabled = true;
        built.ticketSecret = SECRET;
        built.gatewayUrl = "wss://terminal.example.com";
        return built;
    }

    @Test
    void gateDisabledThrowsNotFound() {
        service.enabled = false;

        var e = assertThrows(NotFoundException.class, () -> service.authorize("s1", "sb-1", "u1"));
        assertEquals("TERMINAL_DISABLED", e.errorCode());
    }

    @Test
    void emptyTicketSecretDisablesGateEvenWhenEnabledFlagIsTrue() {
        service.ticketSecret = new byte[0];

        var e = assertThrows(NotFoundException.class, () -> service.authorize("s1", "sb-1", "u1"));
        assertEquals("TERMINAL_DISABLED", e.errorCode());
    }

    @Test
    void blankGatewayUrlDisablesGateEvenWhenEnabledFlagIsTrue() {
        service.gatewayUrl = "   ";

        var e = assertThrows(NotFoundException.class, () -> service.authorize("s1", "sb-1", "u1"));
        assertEquals("TERMINAL_DISABLED", e.errorCode());
    }

    @Test
    void inaccessibleSessionPropagatesForbidden() {
        service = newService((sessionId, userId) -> {
            throw new ForbiddenException("session is unavailable");
        });

        assertThrows(ForbiddenException.class, () -> service.authorize("s1", "sb-1", "u1"));
    }

    @Test
    void replacedSandboxThrowsConflictWithErrorCode() {
        nextStatus = SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.REPLACED;

        var e = assertThrows(ConflictException.class, () -> service.authorize("s1", "sb-1", "u1"));
        assertEquals("SANDBOX_REPLACED", e.errorCode());
    }

    @Test
    void missingSandboxThrowsNotFoundWithExpiredErrorCode() {
        nextStatus = SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.MISSING;

        var e = assertThrows(NotFoundException.class, () -> service.authorize("s1", "sb-1", "u1"));
        assertEquals("SANDBOX_EXPIRED", e.errorCode());
    }

    @Test
    void currentSandboxReturnsRuntimeBoundToResolvedAddress() {
        var runtime = service.authorize("s1", "sb-1", "u1");

        assertEquals("10.0.0.5", runtime.ip());
        assertEquals(8123, runtime.port());
    }

    @Test
    void secondAuthorizeAlwaysResolvesFreshNoCaching() {
        service.authorize("s1", "sb-1", "u1");

        service.authorize("s1", "sb-1", "u1");

        // v2 deliberately has no address cache (a stale cached pod address would poison
        // every ticket minted within a cache window with no feedback path), so every
        // authorize() call resolves fresh.
        assertEquals(2, resolveCalls.get());
    }

    @Test
    void mintTicketResolvesFreshOnEveryCall() {
        service.mintTicket("s1", "sb-1", "u1", "client-1");
        service.mintTicket("s1", "sb-1", "u1", "client-1");

        assertEquals(2, resolveCalls.get());
    }

    @Test
    void recordActivityThrottlesTouchesWithinWindow() {
        service.recordActivity("s1");
        service.recordActivity("s1");

        assertEquals(1, touchCalls.get());
        assertEquals(1, localTouchCalls.get());
    }

    @Test
    void requireAccessibleThrowsWhenGateOff() {
        service.enabled = false;

        var e = assertThrows(NotFoundException.class, () -> service.requireAccessible("s1", "u1"));
        assertEquals("TERMINAL_DISABLED", e.errorCode());
    }

    @Test
    void requireAccessiblePropagatesSessionAccessCheck() {
        service = newService((sessionId, userId) -> {
            throw new ForbiddenException("session is unavailable");
        });

        assertThrows(ForbiddenException.class, () -> service.requireAccessible("s1", "u1"));
    }

    @Test
    void mintTicketProducesAVerifiableTicketBoundToResolvedAddressAndRequestClientId() {
        long before = System.currentTimeMillis() / 1000L;

        var wire = service.mintTicket("s1", "sb-1", "u1", "client-9");

        var verified = TerminalTicketCodec.verify(wire, SECRET, before);
        assertEquals("s1", verified.sid());
        assertEquals("sb-1", verified.sbid());
        assertEquals("client-9", verified.cid());
        assertEquals("10.0.0.5", verified.ip());
        assertEquals(8123, verified.port());
        assertTrue(verified.iat() >= before);
        assertEquals(verified.iat() + 30, verified.exp());
    }

    @Test
    void mintTicketPropagatesGateDisabledWhenSecretEmpty() {
        service.ticketSecret = new byte[0];

        var e = assertThrows(NotFoundException.class, () -> service.mintTicket("s1", "sb-1", "u1", "client-9"));
        assertEquals("TERMINAL_DISABLED", e.errorCode());
    }

    /**
     * Locks the raw-UTF-8-bytes interpretation of {@link #ticketSecret} at the service level:
     * the Go terminal gateway (core-ai-terminal-gateway/main.go) always uses the raw string
     * bytes of TICKET_SECRET as the HMAC key, never hex-decoded, even when the configured
     * secret happens to look like hex (the operationally expected shared-secret format, e.g.
     * `openssl rand -hex 32`). If SandboxTerminalService (or its caller, SessionModule) ever
     * hex-decoded a hex-looking secret again, this test would fail because verifying with the
     * secret string's raw UTF-8 bytes would no longer match what mintTicket actually signed with.
     */
    @Test
    void mintTicketInterpretsHexLookingSecretAsRawBytesNotHexDecoded() {
        var hexLookingSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd";
        service.ticketSecret = hexLookingSecret.getBytes(StandardCharsets.UTF_8);

        var wire = service.mintTicket("s1", "sb-1", "u1", "client-9");

        var verified = TerminalTicketCodec.verify(wire, hexLookingSecret.getBytes(StandardCharsets.UTF_8), 0L);
        assertEquals("s1", verified.sid());
    }
}
