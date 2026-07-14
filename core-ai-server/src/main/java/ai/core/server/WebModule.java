package ai.core.server;

import ai.core.server.web.StaticFileController;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Path;

/**
 * @author stephen
 */
public class WebModule extends Module {

    @Override
    protected void initialize() {
        registerStaticFiles();
    }

    @SuppressFBWarnings("SACM_STATIC_ARRAY_CREATED_IN_METHOD")
    private void registerStaticFiles() {
        var webPath = System.getProperty("core.webPath");
        if (webPath == null) return;
        var webDir = Path.of(webPath);
        if (!webDir.toFile().exists()) return;
        var controller = new StaticFileController(webDir);
        http().route(HTTPMethod.GET, "/favicon.svg", controller::serve);
        http().route(HTTPMethod.GET, "/favicon.ico", controller::serve);
        http().route(HTTPMethod.GET, "/icons.svg", controller::serve);
        http().route(HTTPMethod.GET, "/logo-lockup.svg", controller::serve);
        http().route(HTTPMethod.GET, "/logo-lockup-dark.svg", controller::serve);
        http().route(HTTPMethod.GET, "/apple-touch-icon.png", controller::serveAppleTouchIcon);
        http().route(HTTPMethod.GET, "/assets/:file", controller::serve);
        // iOS Safari legacy probe; reuse favicon.svg to silence 404 noise.
        http().route(HTTPMethod.GET, "/apple-touch-icon-precomposed.png", controller::serveAppleTouchIcon);
        var spaRoutes = new String[]{
            "/", "/login", "/register", "/authorize", "/chat", "/agents", "/sessions",
            "/system-prompts", "/dashboard", "/traces", "/skills",
            "/prompts", "/scheduler", "/tasks", "/tools", "/api-tools",
            "/triggers", "/datasets", "/for-you", "/for-you/artifacts", "/workflows", "/workflows/explore", "/report-issue"
        };
        for (var path : spaRoutes) {
            http().route(HTTPMethod.GET, path, controller::serve);
        }
        http().route(HTTPMethod.GET, "/agents/:id", controller::serve);
        http().route(HTTPMethod.GET, "/agents/:id/memories", controller::serve);
        http().route(HTTPMethod.GET, "/workflows/:id", controller::serve);
        http().route(HTTPMethod.GET, "/workflows/:id/runs", controller::serve);
        http().route(HTTPMethod.GET, "/runs/:id", controller::serve);
        // note: /mcp is NOT an SPA route — core-ai's own MCP server (McpServerModule) owns GET /mcp. The frontend
        // MCP page only works via in-app navigation; serving it on refresh needs a non-clashing path (e.g. /mcp-servers).
        // /mcp/:id does NOT conflict with McpServerModule (which only owns GET /mcp), so it can be a SPA fallback route.
        http().route(HTTPMethod.GET, "/mcp/:id", controller::serve);
        http().route(HTTPMethod.GET, "/system-prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/traces/:id", controller::serve);
        http().route(HTTPMethod.GET, "/skills/:id", controller::serve);
        http().route(HTTPMethod.GET, "/skills/:id/edit", controller::serve);
        http().route(HTTPMethod.GET, "/prompts/:id", controller::serve);
        http().route(HTTPMethod.GET, "/api-tools/:id", controller::serve);
        http().route(HTTPMethod.GET, "/datasets/:id", controller::serve);
        http().route(HTTPMethod.GET, "/datasets/:id/records", controller::serve);
        http().route(HTTPMethod.GET, "/shared/artifacts/:token", controller::serve);
        // nested SPA routes (multi-segment paths that need direct URL access / refresh support)
        http().route(HTTPMethod.GET, "/triggers/webhook", controller::serve);
        http().route(HTTPMethod.GET, "/triggers/schedule", controller::serve);
        http().route(HTTPMethod.GET, "/triggers/channels", controller::serve);
        http().route(HTTPMethod.GET, "/triggers/openclaw", controller::serve);
        http().route(HTTPMethod.GET, "/tools/builtin", controller::serve);
        http().route(HTTPMethod.GET, "/settings", controller::serve);
        http().route(HTTPMethod.GET, "/settings/users", controller::serve);
        http().route(HTTPMethod.GET, "/settings/api-keys", controller::serve);
        http().route(HTTPMethod.GET, "/settings/gateway", controller::serve);
        http().route(HTTPMethod.GET, "/settings/system", controller::serve);
    }
}
