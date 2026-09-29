package ai.core.api.server;

import ai.core.api.server.hubcatalog.HubCatalogResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * One-shot catalog of everything the authenticated user may reach: the MCP tools, the Service API
 * operations and the agent / LLM_CALL definitions, in a single response.
 * <p>
 * It exists so a local consumer — {@code core-ai-cli}, and the {@code core_ai_session} SDK through
 * it — builds its catalog in one round trip instead of one listing per MCP server and per API app.
 * The sandbox hub serves the same shape to a script inside a sandbox, so both places start their
 * script from one catalog call.
 * <p>
 * Each section is gated by the same permission as its own read surface ({@code mcp.call},
 * {@code apitool.call}, {@code chat.use}); a section the caller cannot read comes back empty rather
 * than failing the request, and a caller with none of them is refused.
 *
 * @author stephen
 */
public interface HubCatalogWebService {
    @GET
    @Path("/api/hub/catalog")
    HubCatalogResponse catalog();
}
