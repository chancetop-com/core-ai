package ai.core.server.agenthub;

import ai.core.api.server.AgentHubWebService;
import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.api.server.agenthub.AgentHubLookupRequest;
import ai.core.api.server.agenthub.AgentHubLookupResponse;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.api.server.agenthub.AgentHubSearchRequest;
import ai.core.api.server.agenthub.AgentHubSearchResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * Authenticated entry point of the Agent Hub: every call is scoped to the caller, and only
 * capability summaries ever leave the server.
 *
 * @author stephen
 */
public class AgentHubWebServiceImpl implements AgentHubWebService {
    @Inject
    AgentHubService hubService;
    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubSearchResponse search(AgentHubSearchRequest request) {
        var effective = request != null ? request : new AgentHubSearchRequest();
        var response = new AgentHubSearchResponse();
        response.agents = hubService.search(AuthContext.userId(webContext), effective.query,
                effective.type, effective.source, effective.limit);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubLookupResponse lookup(AgentHubLookupRequest request) {
        var response = new AgentHubLookupResponse();
        response.candidates = hubService.lookup(AuthContext.userId(webContext), request != null ? request.name : null);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubDetail get(String id) {
        return hubService.get(AuthContext.userId(webContext), id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubRunResult run(String id, AgentHubRunRequest request) {
        var userId = AuthContext.userId(webContext);
        var agent = hubService.resolve(userId, id);
        return hubService.run(userId, agent, request != null ? request : new AgentHubRunRequest());
    }

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubRunResult status(String taskId) {
        return hubService.status(AuthContext.userId(webContext), taskId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubRunResult reply(String taskId, AgentHubReplyRequest request) {
        return hubService.reply(AuthContext.userId(webContext), taskId, request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.CHAT_USE)
    public AgentHubRunResult cancel(String taskId) {
        return hubService.cancel(AuthContext.userId(webContext), taskId);
    }
}
