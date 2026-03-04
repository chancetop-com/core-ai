package ai.core.server.tool;

import ai.core.api.server.tool.ListToolCategoriesResponse;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.api.server.tool.ListToolsResponse;
import ai.core.api.server.tool.ToolRegistryView;
import ai.core.api.server.ToolRegistryWebService;
import ai.core.server.domain.ToolRegistry;
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
