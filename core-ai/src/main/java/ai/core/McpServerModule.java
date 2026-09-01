package ai.core;

import ai.core.mcp.server.McpServerService;
import ai.core.mcp.server.McpStreamableHttpController;
import core.framework.http.HTTPMethod;
import core.framework.module.LambdaController;
import core.framework.module.Module;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class McpServerModule extends Module {
    @Override
    protected void initialize() {
        var holder = new McpServerService("core-ai-api-tool-mcp-server", "1.0.0");
        bind(McpServerService.class, holder);
        onShutdown(holder::close);

        // under /api/ prefix so server auth interceptors (session cookie / bearer key) apply;
        // OPTIONS preflight is served by the server's CORS error handler, no explicit route needed
        http().route(HTTPMethod.GET, "/api/api-tools/mcp", (LambdaController) request -> Response.text("{\"name\":\"" + holder.getServerName() + "\",\"version\":\"" + holder.getServerVersion() + "\"}")
                .contentType(core.framework.http.ContentType.APPLICATION_JSON)
                .header("Access-Control-Allow-Origin", "*"));
        http().route(HTTPMethod.POST, "/api/api-tools/mcp", bind(McpStreamableHttpController.class));
    }
}
