package ai.core.api.server.sandbox;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * Interactive sandbox terminal REST surface. All endpoints share the
 * {@code /api/sessions/sandbox-terminal} prefix so they are covered by the
 * same session identity + RBAC checks as the rest of {@code /api/sessions}.
 *
 * @author xander
 */
public interface SandboxTerminalWebService {
    @POST
    @Path("/api/sessions/sandbox-terminal")
    CreateTerminalResponse create(CreateTerminalRequest request);

    @POST
    @Path("/api/sessions/sandbox-terminal/input")
    void input(TerminalInputRequest request);

    @PUT
    @Path("/api/sessions/sandbox-terminal/size")
    void resize(TerminalSizeRequest request);

    @POST
    @Path("/api/sessions/sandbox-terminal/close")
    void close(CloseTerminalRequest request);
}
