package ai.core.server.sandbox.terminal;

import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.session.SessionActivityRegistry;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises SandboxTerminalService's authorization/resolution path via the
 * narrow functional seams (SessionAccessChecker/TerminalRuntimeResolver)
 * instead of real Mongo/Redis collaborators.
 *
 * @author xander
 */
class SandboxTerminalServiceTest {
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
        return built;
    }

    @Test
    void gateDisabledThrowsNotFound() {
        service.enabled = false;

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
    void currentSandboxReturnsClientBoundToResolvedAddress() {
        var client = service.authorize("s1", "sb-1", "u1");

        assertEquals("http://10.0.0.5:8123/terminal/term-1/events", client.eventsUrl("term-1"));
    }

    @Test
    void secondAuthorizeWithinTtlReusesCachedAddress() {
        service.authorize("s1", "sb-1", "u1");

        service.authorize("s1", "sb-1", "u1");

        assertEquals(1, resolveCalls.get());
    }

    @Test
    void invalidateAddressForcesReResolve() {
        service.authorize("s1", "sb-1", "u1");

        service.invalidateAddress("sb-1");
        service.authorize("s1", "sb-1", "u1");

        assertEquals(2, resolveCalls.get());
    }

    @Test
    void recordActivityThrottlesTouchesWithinWindow() {
        service.recordActivity("s1");
        service.recordActivity("s1");

        assertEquals(1, touchCalls.get());
        assertEquals(1, localTouchCalls.get());
    }
}
