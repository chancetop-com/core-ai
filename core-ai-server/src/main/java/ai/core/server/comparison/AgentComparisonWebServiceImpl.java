package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentComparisonView;
import ai.core.api.server.comparison.AgentComparisonWebService;
import ai.core.api.server.comparison.ComparisonSessionContextView;
import ai.core.api.server.comparison.ComparisonVoteView;
import ai.core.api.server.comparison.CreateAgentComparisonRequest;
import ai.core.api.server.comparison.CreateComparisonSessionsResponse;
import ai.core.api.server.comparison.EndAgentComparisonRequest;
import ai.core.api.server.comparison.ListAgentComparisonsRequest;
import ai.core.api.server.comparison.ListAgentComparisonsResponse;
import ai.core.api.server.comparison.StartAgentComparisonRequest;
import ai.core.api.server.comparison.SubmitComparisonVoteRequest;
import ai.core.api.server.comparison.UpdateAgentComparisonRequest;
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
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class AgentComparisonWebServiceImpl implements AgentComparisonWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentComparisonService comparisonService;
    @Inject
    ComparisonSessionService sessionService;

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_COMPARE)
    public AgentComparisonView create(CreateAgentComparisonRequest request) {
        var userId = userId();
        return comparisonService.create(request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_COMPARE)
    public ListAgentComparisonsResponse list(ListAgentComparisonsRequest request) {
        var userId = userId();
        return comparisonService.list(request, userId);
    }

    @Override
    public AgentComparisonView running(String agentId) {
        return comparisonService.running(agentId, userId());
    }

    @Override
    public AgentComparisonView get(String id) {
        return comparisonService.get(id, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_COMPARE)
    public AgentComparisonView update(String id, UpdateAgentComparisonRequest request) {
        return comparisonService.update(id, request, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_COMPARE)
    public AgentComparisonView start(String id, StartAgentComparisonRequest request) {
        return comparisonService.start(id, request, userId());
    }

    @Override
    public CreateComparisonSessionsResponse createSessions(String id) {
        return sessionService.createSessions(id, userId());
    }

    @Override
    public ComparisonSessionContextView bySession(String sessionId) {
        return sessionService.bySession(sessionId, userId());
    }

    @Override
    public ComparisonVoteView vote(String id, SubmitComparisonVoteRequest request) {
        var userId = userId();
        ActionLogContext.put("comparison_id", id);
        return sessionService.vote(id, request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_COMPARE)
    public AgentComparisonView end(String id, EndAgentComparisonRequest request) {
        var userId = userId();
        ActionLogContext.put("comparison_id", id);
        return comparisonService.end(id, request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_COMPARE)
    public void delete(String id) {
        comparisonService.delete(id, userId());
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        ActionLogContext.put("user_id", userId);
        return userId;
    }
}
