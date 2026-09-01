package ai.core.server.sandbox.terminal;

import ai.core.api.server.sandbox.CloseTerminalRequest;
import ai.core.api.server.sandbox.CreateTerminalRequest;
import ai.core.api.server.sandbox.TerminalInputRequest;
import ai.core.server.session.SessionActivityRegistry;
import ai.core.server.web.auth.AuthContext;
import core.framework.api.http.HTTPStatus;
import core.framework.web.WebContext;
import core.framework.web.exception.TooManyRequestsException;
import core.framework.web.service.RemoteServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers SandboxTerminalWebServiceImpl's exception-to-HTTP mapping in
 * isolation from the real sandbox runtime: a stub {@link SandboxTerminalClient}
 * (subclassed since the client is a plain concrete HTTP wrapper with
 * overridable methods) throws the exact client exception under test, and a
 * {@link SandboxTerminalService} subclass short-circuits {@code authorize}
 * to hand the stub straight to the impl -- the gate/access/resolve/cache
 * logic itself is already covered by SandboxTerminalServiceTest.
 *
 * @author xander
 */
class SandboxTerminalWebServiceImplTest {

    @Test
    void createBusyMapsToTooManyRequests() {
        var client = new StubTerminalClient();
        client.createFailure = new TerminalBusyException("terminal runtime busy");
        var impl = newImpl(client);

        assertThrows(TooManyRequestsException.class, () -> impl.create(createRequest()));
    }

    @Test
    void inputGoneMapsToRemoteServiceExceptionWithExpiredErrorCode() {
        var client = new StubTerminalClient();
        client.inputFailure = new TerminalGoneException("terminal gone");
        var impl = newImpl(client);

        var e = assertThrows(RemoteServiceException.class, () -> impl.input(inputRequest()));
        // Same errorCode as the pre-create MISSING case (SandboxTerminalService#authorize):
        // the frontend treats "gone before creation" and "gone mid-session" identically.
        assertEquals(HTTPStatus.GONE, e.status);
        assertEquals("SANDBOX_EXPIRED", e.errorCode());
    }

    @Test
    void inputUnavailableMapsToRemoteServiceExceptionBadGateway() {
        var client = new StubTerminalClient();
        client.inputFailure = new TerminalRuntimeUnavailableException("terminal runtime unreachable");
        var impl = newImpl(client);

        var e = assertThrows(RemoteServiceException.class, () -> impl.input(inputRequest()));
        assertEquals(HTTPStatus.BAD_GATEWAY, e.status);
        assertEquals("TERMINAL_RUNTIME_UNAVAILABLE", e.errorCode());
    }

    @Test
    void createSkipsRuntimeCallWhenHealthCheckFails() {
        var client = new StubTerminalClient();
        client.healthy = false;
        var impl = newImpl(client);

        var e = assertThrows(RemoteServiceException.class, () -> impl.create(createRequest()));

        assertEquals(HTTPStatus.BAD_GATEWAY, e.status);
        assertFalse(client.createCalled, "create must not be attempted on the runtime when health check fails");
    }

    @Test
    void closeSwallowsGoneAndUnavailable() {
        var goneClient = new StubTerminalClient();
        goneClient.closeFailure = new TerminalGoneException("terminal already gone");
        var goneImpl = newImpl(goneClient);
        assertDoesNotThrow(() -> goneImpl.close(closeRequest()));

        var unavailableClient = new StubTerminalClient();
        unavailableClient.closeFailure = new TerminalRuntimeUnavailableException("terminal runtime unreachable");
        var unavailableImpl = newImpl(unavailableClient);
        assertDoesNotThrow(() -> unavailableImpl.close(closeRequest()));
    }

    private SandboxTerminalWebServiceImpl newImpl(SandboxTerminalClient client) {
        var impl = new SandboxTerminalWebServiceImpl();
        impl.webContext = mock(WebContext.class);
        when(impl.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        impl.service = new FixedClientService(client);
        return impl;
    }

    private CreateTerminalRequest createRequest() {
        var request = new CreateTerminalRequest();
        request.sessionId = "s1";
        request.sandboxId = "sb-1";
        request.clientId = "client-1";
        request.rows = 24;
        request.cols = 80;
        return request;
    }

    private TerminalInputRequest inputRequest() {
        var request = new TerminalInputRequest();
        request.sessionId = "s1";
        request.sandboxId = "sb-1";
        request.terminalId = "term-1";
        request.dataBase64 = "aGk=";
        return request;
    }

    private CloseTerminalRequest closeRequest() {
        var request = new CloseTerminalRequest();
        request.sessionId = "s1";
        request.sandboxId = "sb-1";
        request.terminalId = "term-1";
        return request;
    }

    /** Overrides authorize() outright so no gate/access/resolve/cache logic runs; only the impl's own mapping is under test. */
    private static final class FixedClientService extends SandboxTerminalService {
        private final SandboxTerminalClient client;

        FixedClientService(SandboxTerminalClient client) {
            super((sessionId, userId) -> { },
                    (sessionId, sandboxId, userId) -> {
                        throw new UnsupportedOperationException("resolver must not be used when authorize is overridden");
                    },
                    new SessionActivityRegistry(null), sessionId -> { });
            this.enabled = true;
            this.client = client;
        }

        @Override
        public SandboxTerminalClient authorize(String sessionId, String sandboxId, String userId) {
            return client;
        }
    }

    /** Stub client that throws configured exceptions instead of making real HTTP calls. */
    private static final class StubTerminalClient extends SandboxTerminalClient {
        private boolean healthy = true;
        private boolean createCalled;
        private RuntimeException createFailure;
        private RuntimeException inputFailure;
        private RuntimeException closeFailure;

        StubTerminalClient() {
            super("127.0.0.1", 1);
        }

        @Override
        public boolean health() {
            return healthy;
        }

        @Override
        public CreateResult create(String clientId, int rows, int cols) {
            createCalled = true;
            if (createFailure != null) throw createFailure;
            return new CreateResult("term-1", false);
        }

        @Override
        public void input(String terminalId, String dataBase64) {
            if (inputFailure != null) throw inputFailure;
        }

        @Override
        public void close(String terminalId) {
            if (closeFailure != null) throw closeFailure;
        }
    }
}
