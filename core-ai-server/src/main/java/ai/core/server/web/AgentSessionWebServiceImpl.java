package ai.core.server.web;

import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.agent.GenerateAgentDraftResponse;
import ai.core.api.server.session.ApproveToolCallRequest;
import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.session.CreateSessionResponse;
import ai.core.api.server.session.LoadToolsRequest;
import ai.core.api.server.session.LoadToolsResponse;
import ai.core.api.server.session.SendMessageRequest;
import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.api.server.session.SessionStatusResponse;
import ai.core.api.server.session.SessionStatus;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.session.AgentSessionManager;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

import java.util.ArrayList;

/**
 * @author stephen
 */
public class AgentSessionWebServiceImpl implements AgentSessionWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    AgentDraftGenerator agentDraftGenerator;

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        String sessionId;
        if (request.agentId != null) {
            var agent = agentDefinitionService.getEntity(request.agentId);
            sessionId = sessionManager.createSessionFromAgent(agent, request.config, userId);
        } else {
            sessionId = sessionManager.createSession(request.config, userId);
        }

        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        return response;
    }

    @Override
    public void sendMessage(String sessionId, SendMessageRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId);
        session.sendMessage(request.message);
    }

    @Override
    public void approve(String sessionId, ApproveToolCallRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId);
        session.approveToolCall(request.callId, request.decision);
    }

    @Override
    public SessionHistoryResponse history(String sessionId) {
        sessionManager.getSession(sessionId);
        var response = new SessionHistoryResponse();
        response.messages = new ArrayList<>();
        return response;
    }

    @Override
    public SessionStatusResponse status(String sessionId) {
        sessionManager.getSession(sessionId);
        var response = new SessionStatusResponse();
        response.sessionId = sessionId;
        response.status = SessionStatus.IDLE;
        return response;
    }

    @Override
    public void cancel(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId);
        session.cancelTurn();
    }

    @Override
    public GenerateAgentDraftResponse generateAgentDraft(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId);
        return agentDraftGenerator.generate(session);
    }

    @Override
    public LoadToolsResponse loadTools(String sessionId, LoadToolsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var loadedTools = sessionManager.loadTools(sessionId, request.toolIds);
        var response = new LoadToolsResponse();
        response.loadedTools = loadedTools;
        return response;
    }

    @Override
    public void close(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        sessionManager.closeSession(sessionId);
    }
}
