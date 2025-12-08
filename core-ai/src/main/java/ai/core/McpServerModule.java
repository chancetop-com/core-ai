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
        var holder = new McpServerService("core-ai-mcp-server", "1.0.0");
        bind(McpServerService.class, holder);
        onShutdown(holder::close);

        // MCP endpoint with CORS support
        http().route(HTTPMethod.OPTIONS, "/mcp", (LambdaController) request -> Response.empty()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id")
                .header("Access-Control-Max-Age", "86400"));
        http().route(HTTPMethod.GET, "/mcp", (LambdaController) request -> Response.text("{\"name\":\"" + holder.getServerName() + "\",\"version\":\"" + holder.getServerVersion() + "\"}")
                .contentType(core.framework.http.ContentType.APPLICATION_JSON)
                .header("Access-Control-Allow-Origin", "*"));
        http().route(HTTPMethod.POST, "/mcp", bind(McpStreamableHttpController.class));
    }
}
