package ai.core.api.server;

import ai.core.api.server.session.ApproveToolCallRequest;
import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.session.CreateSessionResponse;
import ai.core.api.server.session.SendMessageRequest;
import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.api.server.session.SessionStatusResponse;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface AgentSessionWebService {
    @POST
    @Path("/api/sessions")
    @ResponseStatus(HTTPStatus.CREATED)
    CreateSessionResponse create(CreateSessionRequest request);

    @POST
    @Path("/api/sessions/:sessionId/messages")
    void sendMessage(@PathParam("sessionId") String sessionId, SendMessageRequest request);

    @POST
    @Path("/api/sessions/:sessionId/approve")
    void approve(@PathParam("sessionId") String sessionId, ApproveToolCallRequest request);

    @GET
    @Path("/api/sessions/:sessionId/history")
    SessionHistoryResponse history(@PathParam("sessionId") String sessionId);

    @GET
    @Path("/api/sessions/:sessionId/status")
    SessionStatusResponse status(@PathParam("sessionId") String sessionId);

    @POST
    @Path("/api/sessions/:sessionId/cancel")
    void cancel(@PathParam("sessionId") String sessionId);

    @DELETE
    @Path("/api/sessions/:sessionId")
    void close(@PathParam("sessionId") String sessionId);
}
