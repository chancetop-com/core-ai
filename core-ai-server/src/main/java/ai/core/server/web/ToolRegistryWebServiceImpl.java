package ai.core.server.web;

import ai.core.api.server.ToolRegistryWebService;
import ai.core.api.server.tool.CreateMcpServerRequest;
import ai.core.api.server.tool.ListToolCategoriesResponse;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.api.server.tool.ListToolsResponse;
import ai.core.api.server.tool.ToolRegistryView;
import ai.core.api.server.tool.UpdateMcpServerRequest;
import ai.core.server.domain.ToolRegistry;
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
