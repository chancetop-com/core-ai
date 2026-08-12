package ai.core.server.web;

import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.tool.BuiltinGroupToolsResponse;
import ai.core.api.server.tool.CreateMcpServerRequest;
import ai.core.api.server.tool.ImportMcpServersRequest;
import ai.core.api.server.tool.ImportMcpServersResponse;
import ai.core.api.server.tool.ListApiAppServicesResponse;
import ai.core.api.server.tool.ListApiAppsResponse;
import ai.core.api.server.tool.ListToolCategoriesResponse;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.api.server.tool.ListToolsResponse;
import ai.core.api.server.tool.McpServerStatusResponse;
import ai.core.api.server.tool.McpServerToolsResponse;
import ai.core.api.server.tool.TestApiToolRequest;
import ai.core.api.server.tool.TestApiToolResponse;
import ai.core.api.server.tool.TestMcpToolRequest;
import ai.core.api.server.tool.TestMcpToolResponse;
import ai.core.api.server.tool.ToolRegistryView;
import ai.core.api.server.tool.UpdateMcpServerRequest;
import ai.core.mcp.client.McpClientManager;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.tool.InternalApiToolLoader;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.session.SessionIdentity;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class ToolRegistryWebServiceImpl implements ToolRegistryWebService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryWebServiceImpl.class);
    private static final String MASKED_SECRET = "***";

    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    WebContext webContext;
    @Inject
    PermissionService permissionService;
    @Inject
    SessionIdentity sessionIdentity;

    @Override
    @PermissionsBypass
    public ListToolsResponse list(ListToolsRequest request) {
        var userId = AuthContext.userId(webContext);
        var tools = toolRegistryService.listTools(request.category);
        var visible = tools.stream()
                .filter(tool -> tool.type != ToolType.MCP || hasMcpView(userId))
                .toList();
        var response = new ListToolsResponse();
        response.tools = visible.stream().map(tool -> toView(tool, userId)).toList();
        response.total = (long) response.tools.size();
        return response;
    }

    @Override
    @PermissionsBypass
    public ToolRegistryView get(String id) {
        var userId = AuthContext.userId(webContext);
        var entity = toolRegistryService.getTool(id);
        if (entity.type == ToolType.MCP) {
            if (!hasMcpView(userId)) {
                throw new ForbiddenException("permission required: " + PermissionCodes.MCP_VIEW);
            }
        } else if (entity.type == ToolType.BUILTIN) {
            if (!sessionIdentity.has(PermissionCodes.TOOL_VIEW)) permissionService.check(userId, PermissionCodes.TOOL_VIEW);
        } else {
            if (!sessionIdentity.has(PermissionCodes.APITOOL_VIEW)) permissionService.check(userId, PermissionCodes.APITOOL_VIEW);
        }
        return toView(entity, userId);
    }

    @Override
    @PermissionsBypass
    public ListToolCategoriesResponse categories() {
        requireAnyToolView(AuthContext.userId(webContext));
        var response = new ListToolCategoriesResponse();
        response.categories = toolRegistryService.listCategories();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public ToolRegistryView createMcpServer(CreateMcpServerRequest request) {
        return toView(toolRegistryService.createMcpServer(request.name, request.description, request.category, request.config, request.enabled), AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public ImportMcpServersResponse importMcpServers(ImportMcpServersRequest request) {
        var created = toolRegistryService.importMcpServers(request.config, request.category, request.enabled);
        var response = new ImportMcpServersResponse();
        response.servers = created.stream().map(entity -> toView(entity, AuthContext.userId(webContext))).toList();
        response.total = response.servers.size();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public ToolRegistryView updateMcpServer(String id, UpdateMcpServerRequest request) {
        return toView(toolRegistryService.updateMcpServer(id, request), AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public void deleteMcpServer(String id) {
        toolRegistryService.deleteMcpServer(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public ToolRegistryView enableMcpServer(String id) {
        return toView(toolRegistryService.enableMcpServer(id), AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public ToolRegistryView disableMcpServer(String id) {
        return toView(toolRegistryService.disableMcpServer(id), AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_VIEW)
    public McpServerToolsResponse listMcpServerTools(String id) {
        var watch = new StopWatch();
        var entity = toolRegistryService.getTool(id);
        var toolDetails = toolRegistryService.listMcpServerToolDetails(id);
        var response = new McpServerToolsResponse();
        response.serverId = id;
        response.serverName = entity.name;
        response.tools = toolDetails.stream().map(t -> {
            var info = new McpServerToolsResponse.McpToolInfo();
            info.name = t.name();
            info.description = t.description();
            info.inputSchema = t.inputSchema() != null ? JsonUtil.toJsonNotOnlyPublic(t.inputSchema()) : null;
            return info;
        }).toList();
        LOGGER.debug("listMcpServerTools completed, id={}, tools={}, elapsed={}", id, response.tools.size(), watch.elapsed());
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.TOOL_VIEW)
    public BuiltinGroupToolsResponse listBuiltinGroupTools(String id) {
        var entity = toolRegistryService.getTool(id);
        var tools = toolRegistryService.listBuiltinGroupTools(id);
        var response = new BuiltinGroupToolsResponse();
        response.groupId = id;
        response.groupName = entity.name;
        response.tools = tools.stream().map(t -> {
            var info = new BuiltinGroupToolsResponse.ToolInfo();
            info.name = t.name();
            info.description = t.description();
            info.inputSchema = t.inputSchema();
            return info;
        }).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_VIEW)
    public McpServerStatusResponse getMcpServerStatus(String id) {
        var watch = new StopWatch();
        var entity = toolRegistryService.getTool(id);
        var state = toolRegistryService.getMcpServerState(id);
        var response = new McpServerStatusResponse();
        response.serverId = id;
        response.state = state.name();
        response.message = mcpStatusMessage(state, entity.enabled);
        LOGGER.debug("getMcpServerStatus completed, id={}, state={}, elapsed={}", id, state, watch.elapsed());
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public McpServerStatusResponse connectMcpServer(String id) {
        var state = toolRegistryService.connectMcpServer(id);
        var response = new McpServerStatusResponse();
        response.serverId = id;
        response.state = state.name();
        response.message = mcpStatusMessage(state, true);
        return response;
    }

    private String mcpStatusMessage(McpClientManager.ConnectionState state, boolean enabled) {
        if (!enabled) return "server is disabled";
        if (state == McpClientManager.ConnectionState.FAILED) {
            return "Connection failed. Check the server URL and credentials, save changes, then retry.";
        }
        if (state == McpClientManager.ConnectionState.CONNECTING
            || state == McpClientManager.ConnectionState.NOT_CONNECTED
            || state == McpClientManager.ConnectionState.RECONNECTING) {
            return "Connection in progress, check status endpoint for completion";
        }
        return null;
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_MANAGE)
    public TestMcpToolResponse testMcpServerTool(String id, TestMcpToolRequest request) {
        if (request == null || request.toolName == null || request.toolName.isBlank()) {
            throw new IllegalArgumentException("tool_name is required");
        }
        var start = System.currentTimeMillis();
        var result = toolRegistryService.callMcpServerTool(id, request.toolName, request.arguments);
        var response = new TestMcpToolResponse();
        response.success = result.getStatus() == ToolCallResult.Status.COMPLETED;
        response.result = result.toResultForLLM();
        response.durationMs = System.currentTimeMillis() - start;
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_VIEW)
    public ListApiAppsResponse listServiceApiApps() {
        var apps = toolRegistryService.listServiceApiApps();
        var response = new ListApiAppsResponse();
        response.apps = apps.stream().map(this::toApiAppView).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_VIEW)
    public ListApiAppServicesResponse listApiAppServices(String appName) {
        var services = toolRegistryService.listApiAppServices(appName);
        var response = new ListApiAppServicesResponse();
        response.services = services.stream().map(s -> {
            var view = new ListApiAppServicesResponse.ApiServiceView();
            view.name = s.name();
            view.description = s.description();
            view.operationCount = s.operationCount();
            view.operations = s.operations().stream().map(op -> {
                var opView = new ListApiAppServicesResponse.ApiOperationView();
                opView.name = op.name();
                opView.toolName = op.toolName();
                opView.description = op.description();
                opView.method = op.method();
                opView.path = op.path();
                opView.requestType = op.requestType();
                opView.responseType = op.responseType();
                opView.inputSchema = op.inputSchema();
                opView.outputSchema = op.outputSchema();
                return opView;
            }).toList();
            return view;
        }).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
    public TestApiToolResponse testServiceApiTool(TestApiToolRequest request) {
        if (request == null || request.toolId == null || request.toolId.isBlank()) {
            throw new IllegalArgumentException("tool_id is required");
        }
        var start = System.currentTimeMillis();
        var result = toolRegistryService.callServiceApiTool(request.toolId, request.arguments);
        var response = new TestApiToolResponse();
        response.success = result.getStatus() == ToolCallResult.Status.COMPLETED;
        response.result = result.toResultForLLM();
        response.durationMs = System.currentTimeMillis() - start;
        return response;
    }

    private void requireAnyToolView(String userId) {
        if (sessionIdentity.hasAny(PermissionCodes.TOOL_VIEW, PermissionCodes.MCP_VIEW, PermissionCodes.APITOOL_VIEW)) return;
        if (!permissionService.has(userId, PermissionCodes.TOOL_VIEW)
            && !permissionService.has(userId, PermissionCodes.MCP_VIEW)
            && !permissionService.has(userId, PermissionCodes.APITOOL_VIEW)) {
            throw new ForbiddenException("permission required: tool.view or mcp.view or apitool.view");
        }
    }

    private boolean hasMcpView(String userId) {
        if (sessionIdentity.has(PermissionCodes.MCP_VIEW)) return true;
        return permissionService.has(userId, PermissionCodes.MCP_VIEW);
    }

    private boolean hasMcpManage(String userId) {
        if (sessionIdentity.has(PermissionCodes.MCP_MANAGE)) return true;
        return permissionService.has(userId, PermissionCodes.MCP_MANAGE);
    }

    private ListApiAppsResponse.ApiAppView toApiAppView(InternalApiToolLoader.ApiAppInfo info) {
        var view = new ListApiAppsResponse.ApiAppView();
        view.name = info.app();
        view.baseUrl = info.baseUrl();
        view.version = info.version();
        view.description = info.description();
        return view;
    }

    /**
     * MCP entries are masked for viewers: config secret keys (headers/env) and raw_config
     * are hidden unless the caller has {@code mcp.manage}; without {@code mcp.view} the
     * entry is filtered out entirely by the caller.
     */
    private ToolRegistryView toView(ToolRegistryEntry entity, String userId) {
        var view = new ToolRegistryView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.type = entity.type.name();
        view.category = entity.category;
        if (entity.type == ToolType.MCP && !hasMcpManage(userId)) {
            view.config = maskMcpConfig(entity.config);
            view.rawConfig = null;
        } else {
            view.config = entity.config;
            view.rawConfig = entity.rawConfig;
        }
        view.enabled = entity.enabled;
        return view;
    }

    private Map<String, String> maskMcpConfig(Map<String, String> config) {
        if (config == null || config.isEmpty()) return Map.of();
        var masked = new HashMap<String, String>(config);
        for (var key : List.of("headers", "env")) {
            if (masked.containsKey(key)) masked.put(key, MASKED_SECRET);
        }
        return masked;
    }
}
