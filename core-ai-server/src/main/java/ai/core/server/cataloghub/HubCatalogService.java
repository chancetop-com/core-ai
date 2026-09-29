package ai.core.server.cataloghub;

import ai.core.api.server.hubcatalog.HubCatalogResponse;
import ai.core.api.server.hubcatalog.HubCatalogSource;
import ai.core.api.server.hubcatalog.HubCatalogTool;
import ai.core.server.agenthub.AgentCatalogService;
import ai.core.server.apitoolhub.ApiToolCatalogService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.DefinitionType;
import ai.core.server.mcphub.McpToolCatalogService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.session.SessionIdentity;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Composes the three hub catalogs into one response: every MCP tool, every Service API operation and
 * the visible agent / LLM_CALL definitions. A local client builds its whole catalog from this single
 * call, instead of one listing per MCP server and per API app.
 * <p>
 * A section is included only when the caller holds the permission its own read surface requires
 * ({@code mcp.call}, {@code apitool.call}, {@code chat.use}). The check mirrors
 * {@code PermissionInterceptor}: a session identity's permissions first, then {@link PermissionService}
 * for API keys. The endpoint therefore never widens what the individual hub endpoints allow.
 *
 * @author stephen
 */
public class HubCatalogService {
    @Inject
    McpToolCatalogService mcpCatalog;
    @Inject
    ApiToolCatalogService apiCatalog;
    @Inject
    AgentCatalogService agentCatalog;
    @Inject
    SessionIdentity sessionIdentity;
    @Inject
    PermissionService permissionService;
    @Inject
    WebContext webContext;

    public HubCatalogResponse catalog() {
        var userId = AuthContext.userId(webContext);
        var sources = new ArrayList<HubCatalogSource>();
        var tools = new ArrayList<HubCatalogTool>();
        var sections = new ArrayList<String>();
        if (may(userId, PermissionCodes.MCP_CALL)) {
            sections.add("mcp");
            collectMcp(sources, tools);
        }
        if (may(userId, PermissionCodes.APITOOL_CALL)) {
            sections.add("api");
            collectApi(sources, tools);
        }
        if (may(userId, PermissionCodes.CHAT_USE)) {
            sections.add("agent");
            sections.add("llm_call");
            collectAgents(userId, tools);
        }

        var response = new HubCatalogResponse();
        response.generatedAt = Instant.now().toString();
        response.sections = sections;
        response.sources = sources;
        response.tools = tools;
        return response;
    }

    private void collectMcp(List<HubCatalogSource> sources, List<HubCatalogTool> tools) {
        for (var server : mcpCatalog.listServers()) {
            var source = new HubCatalogSource();
            source.kind = "mcp";
            source.name = server.entry().name;
            source.state = server.state();
            source.count = server.toolCount();
            source.stale = server.stale();
            sources.add(source);
        }
        for (var tool : mcpCatalog.allTools()) {
            var view = new HubCatalogTool();
            view.kind = "mcp";
            view.name = tool.name();
            view.path = tool.qualifiedName();
            view.group = tool.serverName();
            view.refId = tool.refId();
            view.description = tool.description();
            view.stale = tool.stale();
            tools.add(view);
        }
    }

    private void collectApi(List<HubCatalogSource> sources, List<HubCatalogTool> tools) {
        for (var app : apiCatalog.apps()) {
            var source = new HubCatalogSource();
            source.kind = "api";
            source.name = app.app();
            source.count = app.operationCount();
            sources.add(source);
        }
        for (var operation : apiCatalog.allOperations()) {
            var view = new HubCatalogTool();
            view.kind = "api";
            view.name = operation.name();
            view.path = operation.qualifiedName();
            view.group = operation.app();
            view.refId = operation.refId();
            view.description = operation.description();
            tools.add(view);
        }
    }

    private void collectAgents(String userId, List<HubCatalogTool> tools) {
        for (var agent : agentCatalog.catalog(userId)) {
            var definition = agent.definition();
            if (definition.type != DefinitionType.AGENT && definition.type != DefinitionType.LLM_CALL) continue;
            var view = new HubCatalogTool();
            view.kind = definition.type.name().toLowerCase(Locale.ROOT);
            view.name = agent.name();
            view.path = agent.name();
            view.refId = agent.id();
            view.description = agent.description();
            tools.add(view);
        }
    }

    /** The interceptor's resolution order, so a per-section check answers what the route check would. */
    private boolean may(String userId, String permission) {
        if (sessionIdentity.hasAny(permission)) return true;
        return permissionService.has(userId, permission);
    }
}
