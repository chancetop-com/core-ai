package ai.core.api.server.conversation;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Direct conversation surface for clients that own a root agent session (desktop, future CLI).
 * It reuses the session runtime and stores behind {@code /api/sessions}; the facade adds
 * idempotent client commands, a stable conversation listing and a protocol capability probe.
 *
 * @author core-ai
 */
public interface ConversationWebService {
    @GET
    @Path("/api/conversations/v1/capabilities")
    ConversationCapabilitiesResponse capabilities();

    @GET
    @Path("/api/conversations/v1/agents")
    ConversationAgentListResponse agents(ConversationAgentListRequest request);

    @GET
    @Path("/api/conversations/v1/sessions")
    ConversationSessionListResponse sessions(ConversationSessionListRequest request);

    @POST
    @Path("/api/conversations/v1/sessions")
    ConversationCreateSessionResponse create(ConversationCreateSessionRequest request);

    @GET
    @Path("/api/conversations/v1/sessions/:sessionId")
    ConversationSnapshotResponse snapshot(@PathParam("sessionId") String sessionId);

    @GET
    @Path("/api/conversations/v1/sessions/:sessionId/history")
    ConversationHistoryResponse history(@PathParam("sessionId") String sessionId, ConversationHistoryRequest request);

    @POST
    @Path("/api/conversations/v1/sessions/:sessionId/messages")
    ConversationSendResponse send(@PathParam("sessionId") String sessionId, ConversationSendRequest request);

    @POST
    @Path("/api/conversations/v1/sessions/:sessionId/cancel")
    ConversationCancelResponse cancel(@PathParam("sessionId") String sessionId, ConversationCancelRequest request);

    @PUT
    @Path("/api/conversations/v1/sessions/:sessionId/title")
    ConversationRenameResponse rename(@PathParam("sessionId") String sessionId, ConversationRenameRequest request);
}
