package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentVersionView;
import ai.core.api.server.comparison.AgentVersionWebService;
import ai.core.api.server.comparison.CreateAgentVersionRequest;
import ai.core.api.server.comparison.ListAgentVersionsRequest;
import ai.core.api.server.comparison.ListAgentVersionsResponse;
import ai.core.api.server.comparison.UpdateAgentVersionRequest;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.AGENT_COMPARE)
public class AgentVersionWebServiceImpl implements AgentVersionWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentVersionService versionService;

    @Override
    public AgentVersionView create(CreateAgentVersionRequest request) {
        return versionService.create(request, userId());
    }

    @Override
    public ListAgentVersionsResponse list(ListAgentVersionsRequest request) {
        return versionService.list(request != null ? request.agentId : null, userId());
    }

    @Override
    public AgentVersionView get(String id) {
        return versionService.get(id, userId());
    }

    @Override
    public AgentVersionView update(String id, UpdateAgentVersionRequest request) {
        return versionService.update(id, request, userId());
    }

    @Override
    public void delete(String id) {
        versionService.delete(id, userId());
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        ActionLogContext.put("user_id", userId);
        return userId;
    }
}
