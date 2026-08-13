package ai.core.api.server.session;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface ChatSessionWebService {
    @GET
    @Path("/api/chat/sessions")
    ListChatSessionsResponse list(ListChatSessionsRequest request);

    @GET
    @Path("/api/chat/sessions/:sessionId")
    ChatSessionSummaryView get(@PathParam("sessionId") String sessionId);

    @DELETE
    @Path("/api/chat/sessions/:sessionId")
    DeleteChatSessionResponse delete(@PathParam("sessionId") String sessionId);

    @POST
    @Path("/api/chat/sessions/batch-delete")
    BatchDeleteChatSessionsResponse batchDelete(BatchDeleteChatSessionsRequest request);

    @PUT
    @Path("/api/chat/sessions/:sessionId")
    UpdateChatSessionTitleResponse update(@PathParam("sessionId") String sessionId, UpdateChatSessionTitleRequest request);

    @PUT
    @Path("/api/chat/sessions/:sessionId/feedback")
    SubmitSessionFeedbackResponse submitFeedback(@PathParam("sessionId") String sessionId, SubmitSessionFeedbackRequest request);
}
