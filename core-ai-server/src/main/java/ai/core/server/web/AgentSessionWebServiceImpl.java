package ai.core.server.web;

import ai.core.api.session.AgentSessionWebService;
import ai.core.api.session.ApproveToolCallRequest;
import ai.core.api.session.CreateSessionRequest;
import ai.core.api.session.CreateSessionResponse;
import ai.core.api.session.SendMessageRequest;
import ai.core.api.session.SessionHistoryResponse;
import ai.core.api.session.SessionStatusResponse;
import ai.core.api.session.SessionStatus;
import ai.core.server.session.AgentSessionManager;
import core.framework.inject.Inject;

import java.util.ArrayList;

/**
 * @author stephen
 */
public class AgentSessionWebServiceImpl implements AgentSessionWebService {

    @Inject
    AgentSessionManager sessionManager;

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        var sessionId = sessionManager.createSession(request.config);
        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        return response;
    }

    @Override
    public void sendMessage(String sessionId, SendMessageRequest request) {
        var session = sessionManager.getSession(sessionId);
        session.sendMessage(request.message);
    }

    @Override
    public void approve(String sessionId, ApproveToolCallRequest request) {
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
    public void close(String sessionId) {
        sessionManager.closeSession(sessionId);
    }
}
