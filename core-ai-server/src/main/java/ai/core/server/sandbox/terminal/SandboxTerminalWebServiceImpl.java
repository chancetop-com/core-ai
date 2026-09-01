package ai.core.server.sandbox.terminal;

import ai.core.api.server.sandbox.SandboxTerminalWebService;
import ai.core.api.server.sandbox.TerminalActivityRequest;
import ai.core.api.server.sandbox.TerminalTicketRequest;
import ai.core.api.server.sandbox.TerminalTicketResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * REST surface for the interactive sandbox terminal ticket/activity
 * endpoints. All authorization, sandbox-runtime resolution, and ticket
 * signing is delegated to {@link SandboxTerminalService}; this class only
 * adapts the caller's identity and shapes the HTTP request/response.
 *
 * @author xander
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class SandboxTerminalWebServiceImpl implements SandboxTerminalWebService {
    @Inject
    WebContext webContext;
    @Inject
    SandboxTerminalService service;

    @Override
    public TerminalTicketResponse ticket(TerminalTicketRequest request) {
        var userId = userId();
        var ticket = service.mintTicket(request.sessionId, request.sandboxId, userId, request.clientId);
        service.recordActivity(request.sessionId);

        var response = new TerminalTicketResponse();
        response.ticket = ticket;
        response.gatewayUrl = service.gatewayUrl;
        return response;
    }

    @Override
    public void activity(TerminalActivityRequest request) {
        service.requireAccessible(request.sessionId, userId());
        service.recordActivity(request.sessionId);
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
