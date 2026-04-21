package ai.core.server.web;

import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.tool.CreateMcpServerRequest;
import ai.core.api.server.tool.ListApiAppServicesResponse;
import ai.core.api.server.tool.ListApiAppsResponse;
import ai.core.api.server.tool.ListToolCategoriesResponse;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.api.server.tool.ListToolsResponse;
import ai.core.api.server.tool.ToolRegistryView;
import ai.core.api.server.tool.UpdateMcpServerRequest;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.tool.InternalApiToolLoader;
import ai.core.server.tool.ToolRegistryService;
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
    public ToolRegistryView updateMcpServer(String id, UpdateMcpServerRequest request) {
        return toView(toolRegistryService.updateMcpServer(id, request.name, request.description, request.category, request.config, request.enabled));
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
                opView.description = op.description();
                opView.method = op.method();
                opView.path = op.path();
                return opView;
            }).toList();
            return view;
        }).toList();
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

    private ToolRegistryView toView(ToolRegistry entity) {
        var view = new ToolRegistryView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.type = entity.type.name();
        view.category = entity.category;
        view.config = entity.config;
        view.enabled = entity.enabled;
        return view;
    }
}
