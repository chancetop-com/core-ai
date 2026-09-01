package ai.core.api.server.sandbox;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * Interactive sandbox terminal REST surface. Terminal I/O itself flows over a
 * WebSocket straight from the browser to the terminal gateway (a one-shot,
 * server-signed ticket authorizes that connection); core-ai-server only mints
 * tickets and records renewal activity. Both endpoints share the
 * {@code /api/sessions/sandbox-terminal} prefix so they are covered by the
 * same session identity + RBAC checks as the rest of {@code /api/sessions}.
 *
 * @author xander
 */
public interface SandboxTerminalWebService {
    @POST
    @Path("/api/sessions/sandbox-terminal/ticket")
    TerminalTicketResponse ticket(TerminalTicketRequest request);

    @POST
    @Path("/api/sessions/sandbox-terminal/activity")
    void activity(TerminalActivityRequest request);
}
