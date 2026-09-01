package ai.core.server.sandbox.terminal;

import ai.core.api.server.sandbox.CloseTerminalRequest;
import ai.core.api.server.sandbox.CreateTerminalRequest;
import ai.core.api.server.sandbox.CreateTerminalResponse;
import ai.core.api.server.sandbox.SandboxTerminalWebService;
import ai.core.api.server.sandbox.TerminalInputRequest;
import ai.core.api.server.sandbox.TerminalSizeRequest;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.api.http.HTTPStatus;
import core.framework.inject.Inject;
import core.framework.log.Severity;
import core.framework.web.WebContext;
import core.framework.web.exception.TooManyRequestsException;
import core.framework.web.service.RemoteServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST surface for the interactive sandbox terminal. All authorization and
 * address resolution is delegated to {@link SandboxTerminalService};
 * this class only maps the sandbox runtime client's exceptions to HTTP.
 * <p>
 * core-ng's {@code core.framework.web.exception} package has no 429 or
 * upstream-5xx exception with a custom errorCode beyond {@link TooManyRequestsException}
 * (fixed errorCode {@code TOO_MANY_REQUESTS}, no message-only constructor
 * for a custom code) and {@link RemoteServiceException} (carries an arbitrary
 * {@link HTTPStatus} + errorCode and is honored by core-ng's HTTPErrorHandler
 * even when thrown directly from a service impl, not only from WebServiceClient).
 * Mapping used here: {@link TerminalBusyException} -&gt; {@link TooManyRequestsException} (429);
 * {@link TerminalGoneException} -&gt; {@link RemoteServiceException} with
 * {@link HTTPStatus#GONE} (410) and errorCode {@code SANDBOX_EXPIRED} (same
 * code as the pre-create MISSING case, so the frontend treats both as "gone");
 * {@link TerminalRuntimeUnavailableException} -&gt; {@link RemoteServiceException}
 * with {@link HTTPStatus#BAD_GATEWAY} (502) and errorCode {@code TERMINAL_RUNTIME_UNAVAILABLE}.
 *
 * @author xander
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class SandboxTerminalWebServiceImpl implements SandboxTerminalWebService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxTerminalWebServiceImpl.class);

    @Inject
    WebContext webContext;
    @Inject
    SandboxTerminalService service;

    @Override
    public CreateTerminalResponse create(CreateTerminalRequest request) {
        var userId = userId();
        var client = authorize(request.sessionId, request.sandboxId, userId);
        if (!client.health()) {
            throw mapClientException(request.sandboxId, new TerminalRuntimeUnavailableException("sandbox terminal runtime failed health check"));
        }
        try {
            var result = client.create(request.clientId, request.rows, request.cols);
            service.recordActivity(request.sessionId);
            var response = new CreateTerminalResponse();
            response.terminalId = result.terminalId();
            response.recovered = result.recovered();
            return response;
        } catch (RuntimeException e) {
            throw mapClientException(request.sandboxId, e);
        }
    }

    @Override
    public void input(TerminalInputRequest request) {
        var userId = userId();
        var client = authorize(request.sessionId, request.sandboxId, userId);
        try {
            client.input(request.terminalId, request.dataBase64);
            service.recordActivity(request.sessionId);
        } catch (RuntimeException e) {
            throw mapClientException(request.sandboxId, e);
        }
    }

    @Override
    public void resize(TerminalSizeRequest request) {
        var userId = userId();
        var client = authorize(request.sessionId, request.sandboxId, userId);
        try {
            client.resize(request.terminalId, request.rows, request.cols);
        } catch (RuntimeException e) {
            throw mapClientException(request.sandboxId, e);
        }
    }

    @Override
    public void close(CloseTerminalRequest request) {
        var userId = userId();
        var client = authorize(request.sessionId, request.sandboxId, userId);
        try {
            client.close(request.terminalId);
        } catch (TerminalGoneException | TerminalRuntimeUnavailableException e) {
            // best-effort teardown: a panel closing against an already-dead sandbox
            // must not surface a 502/410 to the user, just log and move on.
            LOGGER.info("terminal close against unavailable runtime, sandboxId={}, terminalId={}",
                    request.sandboxId, request.terminalId, e);
        }
    }

    private SandboxTerminalClient authorize(String sessionId, String sandboxId, String userId) {
        return service.authorize(sessionId, sandboxId, userId);
    }

    private RuntimeException mapClientException(String sandboxId, RuntimeException e) {
        if (e instanceof TerminalBusyException) {
            return new TooManyRequestsException(e.getMessage());
        }
        if (e instanceof TerminalGoneException) {
            return new RemoteServiceException(e.getMessage(), Severity.WARN, "SANDBOX_EXPIRED", HTTPStatus.GONE, e);
        }
        if (e instanceof TerminalRuntimeUnavailableException) {
            service.invalidateAddress(sandboxId);
            return new RemoteServiceException(e.getMessage(), Severity.WARN, "TERMINAL_RUNTIME_UNAVAILABLE", HTTPStatus.BAD_GATEWAY, e);
        }
        return e;
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
