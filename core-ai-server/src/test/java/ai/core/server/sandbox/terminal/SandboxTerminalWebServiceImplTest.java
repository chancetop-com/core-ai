package ai.core.server.sandbox.terminal;

import ai.core.api.server.sandbox.TerminalActivityRequest;
import ai.core.api.server.sandbox.TerminalTicketRequest;
import ai.core.server.session.SessionActivityRegistry;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers SandboxTerminalWebServiceImpl's request/response shaping and caller
 * identity plumbing in isolation from SandboxTerminalService's own gate/
 * access/resolve/cache/mint logic, which is already covered by
 * SandboxTerminalServiceTest: a {@link SandboxTerminalService} subclass
 * overrides mintTicket/requireAccessible/recordActivity to record what the
 * impl passed through instead of exercising the real seams.
 *
 * @author xander
 */
class SandboxTerminalWebServiceImplTest {

    @Test
    void ticketReturnsMintedTicketAndConfiguredGatewayUrlAndRecordsActivity() {
        var service = new RecordingService();
        service.gatewayUrl = "wss://terminal.example.com";
        var impl = newImpl(service);

        var response = impl.ticket(ticketRequest());

        assertEquals("minted-ticket-for-client-1", response.ticket);
        assertEquals("wss://terminal.example.com", response.gatewayUrl);
        assertEquals(1, service.recordActivityCalls.get());
    }

    @Test
    void ticketPassesCallerIdentityAndRequestFieldsToService() {
        var service = new RecordingService();
        var impl = newImpl(service);

        impl.ticket(ticketRequest());

        assertEquals("s1", service.lastSessionId);
        assertEquals("sb-1", service.lastSandboxId);
        assertEquals("user-1", service.lastUserId);
        assertEquals("client-1", service.lastClientId);
    }

    @Test
    void activityChecksAccessThenRecordsActivity() {
        var service = new RecordingService();
        var impl = newImpl(service);

        impl.activity(activityRequest());

        assertEquals("s1", service.accessCheckedSessionId);
        assertEquals("user-1", service.accessCheckedUserId);
        assertEquals(1, service.recordActivityCalls.get());
    }

    @Test
    void activityPropagatesDisabledGate() {
        var service = new RecordingService();
        service.accessDenied = new NotFoundException("sandbox terminal is disabled", "TERMINAL_DISABLED");
        var impl = newImpl(service);

        var e = assertThrows(NotFoundException.class, () -> impl.activity(activityRequest()));
        assertEquals("TERMINAL_DISABLED", e.errorCode());
        assertEquals(0, service.recordActivityCalls.get());
    }

    private SandboxTerminalWebServiceImpl newImpl(SandboxTerminalService service) {
        var impl = new SandboxTerminalWebServiceImpl();
        impl.webContext = mock(WebContext.class);
        when(impl.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        impl.service = service;
        return impl;
    }

    private TerminalTicketRequest ticketRequest() {
        var request = new TerminalTicketRequest();
        request.sessionId = "s1";
        request.sandboxId = "sb-1";
        request.clientId = "client-1";
        return request;
    }

    private TerminalActivityRequest activityRequest() {
        var request = new TerminalActivityRequest();
        request.sessionId = "s1";
        return request;
    }

    /** Records calls instead of exercising the real gate/access/resolve/mint logic (covered by SandboxTerminalServiceTest). */
    private static final class RecordingService extends SandboxTerminalService {
        final AtomicInteger recordActivityCalls = new AtomicInteger();
        String lastSessionId;
        String lastSandboxId;
        String lastUserId;
        String lastClientId;
        String accessCheckedSessionId;
        String accessCheckedUserId;
        RuntimeException accessDenied;

        RecordingService() {
            super((sessionId, userId) -> { },
                    (sessionId, sandboxId, userId) -> {
                        throw new UnsupportedOperationException("resolver must not be used when mintTicket/requireAccessible are overridden");
                    },
                    new SessionActivityRegistry(null), sessionId -> { });
            this.enabled = true;
        }

        @Override
        public String mintTicket(String sessionId, String sandboxId, String userId, String clientId) {
            this.lastSessionId = sessionId;
            this.lastSandboxId = sandboxId;
            this.lastUserId = userId;
            this.lastClientId = clientId;
            return "minted-ticket-for-" + clientId;
        }

        @Override
        public void requireAccessible(String sessionId, String userId) {
            if (accessDenied != null) throw accessDenied;
            this.accessCheckedSessionId = sessionId;
            this.accessCheckedUserId = userId;
        }

        @Override
        public void recordActivity(String sessionId) {
            recordActivityCalls.incrementAndGet();
        }
    }
}
