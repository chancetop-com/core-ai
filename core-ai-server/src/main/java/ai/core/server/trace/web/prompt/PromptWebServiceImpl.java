package ai.core.server.trace.web.prompt;

import ai.core.api.server.prompt.ListPromptsRequest;
import ai.core.api.server.prompt.ListPromptsResponse;
import ai.core.api.server.prompt.PromptStatusView;
import ai.core.api.server.prompt.PromptTemplateView;
import ai.core.api.server.prompt.PromptWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.trace.domain.PromptStatus;
import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trace.service.PromptService;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

/**
 * @author stephen
 */
public class PromptWebServiceImpl implements PromptWebService {
    private static PromptTemplateView toView(PromptTemplate entity) {
        var view = new PromptTemplateView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.template = entity.template;
        view.variables = entity.variables;
        view.model = entity.model;
        view.modelParameters = entity.modelParameters;
        view.version = entity.version;
        view.publishedVersion = entity.publishedVersion;
        view.status = entity.status != null ? PromptStatusView.valueOf(entity.status.name()) : null;
        view.tags = entity.tags;
        view.createdBy = entity.createdBy;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    private static PromptTemplate toEntity(PromptTemplateView view) {
        var entity = new PromptTemplate();
        entity.id = view.id;
        entity.name = view.name;
        entity.description = view.description;
        entity.template = view.template;
        entity.variables = view.variables;
        entity.model = view.model;
        entity.modelParameters = view.modelParameters;
        entity.tags = view.tags;
        entity.status = view.status != null ? PromptStatus.valueOf(view.status.name()) : null;
        return entity;
    }

    private static int parseOffset(Integer offset) {
        return offset == null ? 0 : offset;
    }

    private static int parseLimit(Integer limit) {
        return limit == null ? 20 : limit;
    }

    @Inject
    PromptService promptService;

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public ListPromptsResponse list(ListPromptsRequest request) {
        var prompts = promptService.list(parseOffset(request.offset), parseLimit(request.limit));
        var response = new ListPromptsResponse();
        response.prompts = prompts.stream().map(PromptWebServiceImpl::toView).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public PromptTemplateView create(PromptTemplateView request) {
        var created = promptService.create(toEntity(request));
        return toView(created);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public PromptTemplateView get(String promptId) {
        var prompt = promptService.get(promptId);
        if (prompt == null) throw new NotFoundException("prompt not found: " + promptId);
        return toView(prompt);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public PromptTemplateView update(String promptId, PromptTemplateView request) {
        var updated = promptService.update(promptId, toEntity(request));
        return toView(updated);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public void delete(String promptId) {
        promptService.delete(promptId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public PromptTemplateView publish(String promptId) {
        var published = promptService.publish(promptId);
        return toView(published);
    }
}
