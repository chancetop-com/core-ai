package ai.core.server.web;

import ai.core.api.server.ToolRegistryWebService;
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
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.tool.InternalApiToolLoader;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class ToolRegistryWebServiceImpl implements ToolRegistryWebService {
    @Inject
    ToolRegistryService toolRegistryService;

    @Override
    public ListToolsResponse list(ListToolsRequest request) {
        var tools = toolRegistryService.listTools(request.category);
        var response = new ListToolsResponse();
        response.tools = tools.stream().map(this::toView).toList();
        response.total = (long) response.tools.size();
        return response;
    }

    @Override
    public ToolRegistryView get(String id) {
        return toView(toolRegistryService.getTool(id));
    }

    @Override
    public ListToolCategoriesResponse categories() {
        var response = new ListToolCategoriesResponse();
        response.categories = toolRegistryService.listCategories();
        return response;
    }

    @Override
    public ToolRegistryView createMcpServer(CreateMcpServerRequest request) {
        return toView(toolRegistryService.createMcpServer(request.name, request.description, request.category, request.config, request.enabled));
    }

    @Override
    public ImportMcpServersResponse importMcpServers(ImportMcpServersRequest request) {
        var created = toolRegistryService.importMcpServers(request.config, request.category, request.enabled);
        var response = new ImportMcpServersResponse();
        response.servers = created.stream().map(this::toView).toList();
        response.total = response.servers.size();
        return response;
    }

    @Override
    public ToolRegistryView updateMcpServer(String id, UpdateMcpServerRequest request) {
        return toView(toolRegistryService.updateMcpServer(id, request.name, request.description, request.category, request.config, request.enabled, request.rawConfig));
    }

    @Override
    public void deleteMcpServer(String id) {
        toolRegistryService.deleteMcpServer(id);
    }

    @Override
    public ToolRegistryView enableMcpServer(String id) {
        return toView(toolRegistryService.enableMcpServer(id));
    }

    @Override
    public ToolRegistryView disableMcpServer(String id) {
        return toView(toolRegistryService.disableMcpServer(id));
    }

    @Override
    public McpServerToolsResponse listMcpServerTools(String id) {
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
        return response;
    }

    @Override
    public McpServerStatusResponse getMcpServerStatus(String id) {
        var entity = toolRegistryService.getTool(id);
        var state = toolRegistryService.getMcpServerState(id);
        var response = new McpServerStatusResponse();
        response.serverId = id;
        response.state = state.name();
        response.message = entity.enabled ? null : "server is disabled";
        return response;
    }

    @Override
    public McpServerStatusResponse connectMcpServer(String id) {
        var state = toolRegistryService.connectMcpServer(id);
        var response = new McpServerStatusResponse();
        response.serverId = id;
        response.state = state.name();
        return response;
    }

    @Override
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
    public ListApiAppsResponse listServiceApiApps() {
        var apps = toolRegistryService.listServiceApiApps();
        var response = new ListApiAppsResponse();
        response.apps = apps.stream().map(this::toApiAppView).toList();
        return response;
    }

    @Override
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

    private ListApiAppsResponse.ApiAppView toApiAppView(InternalApiToolLoader.ApiAppInfo info) {
        var view = new ListApiAppsResponse.ApiAppView();
        view.name = info.app();
        view.baseUrl = info.baseUrl();
        view.version = info.version();
        view.description = info.description();
        return view;
    }

    private ToolRegistryView toView(ToolRegistryEntry entity) {
        var view = new ToolRegistryView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.type = entity.type.name();
        view.category = entity.category;
        view.config = entity.config;
        view.rawConfig = entity.rawConfig;
        view.enabled = entity.enabled;
        return view;
    }
}
