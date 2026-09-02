package ai.core.server.systemprompt;

import ai.core.api.server.systemprompt.ListSystemPromptVersionsResponse;
import ai.core.api.server.systemprompt.ListSystemPromptsRequest;
import ai.core.api.server.systemprompt.ListSystemPromptsResponse;
import ai.core.api.server.systemprompt.SystemPromptRequest;
import ai.core.api.server.systemprompt.SystemPromptTestRequest;
import ai.core.api.server.systemprompt.SystemPromptTestResponse;
import ai.core.api.server.systemprompt.SystemPromptView;
import ai.core.api.server.systemprompt.SystemPromptWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class SystemPromptWebServiceImpl implements SystemPromptWebService {
    @Inject
    SystemPromptService systemPromptService;

    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public ListSystemPromptsResponse list(ListSystemPromptsRequest request) {
        int offset = request.offset == null ? 0 : request.offset;
        int limit = request.limit == null ? 20 : request.limit;
        var response = new ListSystemPromptsResponse();
        response.prompts = systemPromptService.list(offset, limit);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public SystemPromptView create(SystemPromptRequest request) {
        return systemPromptService.create(request, resolveUserId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public SystemPromptView getByName(String name) {
        return systemPromptService.getByName(name);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public SystemPromptView get(String promptId) {
        return systemPromptService.get(promptId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public SystemPromptView update(String promptId, SystemPromptRequest request) {
        return systemPromptService.update(promptId, request, resolveUserId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_MANAGE)
    public void delete(String promptId) {
        systemPromptService.delete(promptId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public ListSystemPromptVersionsResponse versions(String promptId) {
        var response = new ListSystemPromptVersionsResponse();
        response.versions = systemPromptService.versions(promptId);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public SystemPromptView getVersion(String promptId, Integer version) {
        return systemPromptService.getVersion(promptId, version);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROMPT_VIEW)
    public SystemPromptTestResponse test(String promptId, SystemPromptTestRequest request) {
        return systemPromptService.test(promptId, request);
    }

    private String resolveUserId() {
        var userId = AuthContext.userId(webContext);
        return userId != null ? userId : "anonymous";
    }
}
