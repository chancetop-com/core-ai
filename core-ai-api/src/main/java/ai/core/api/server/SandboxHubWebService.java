package ai.core.api.server;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.sandboxhub.SandboxHubCallResponse;
import ai.core.api.server.sandboxhub.SandboxHubCatalogResponse;
import ai.core.api.server.sandboxhub.SandboxHubSessionView;
import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.api.server.sandboxhub.SandboxHubToolSearchRequest;
import ai.core.api.server.sandboxhub.SandboxHubToolsResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Sandbox Hub: the capabilities of the session that owns the calling sandbox, addressed from inside
 * that sandbox (Python SDK or the {@code core-ai-sandbox} CLI, both going through the runtime's
 * loopback {@code /hub} proxy).
 * <p>
 * Auth is a dedicated {@code cst_} session token — the sandbox never holds a user key, an MCP
 * credential or an LLM key. The session id is taken from the token, never from the request, so
 * these endpoints cannot address another session. The catalog is exactly the session agent's own
 * tool set with the runtime-internal tools removed; raw LLM/embeddings access is deliberately
 * absent (LLM work goes through published {@code llm_call} definitions).
 *
 * @author stephen
 */
public interface SandboxHubWebService {
    @GET
    @Path("/api/sandbox-hub/me")
    SandboxHubSessionView me();

    @GET
    @Path("/api/sandbox-hub/catalog")
    SandboxHubCatalogResponse catalog();

    @GET
    @Path("/api/sandbox-hub/tools")
    SandboxHubToolsResponse tools(SandboxHubToolSearchRequest request);

    @GET
    @Path("/api/sandbox-hub/tools/:name")
    SandboxHubToolDetail describe(@PathParam("name") String name);

    @POST
    @Path("/api/sandbox-hub/tools/:name/call")
    SandboxHubCallResponse call(@PathParam("name") String name, HubCallRequest request);

    @GET
    @Path("/api/sandbox-hub/tasks/:task_id")
    SandboxHubCallResponse task(@PathParam("task_id") String taskId);
}
