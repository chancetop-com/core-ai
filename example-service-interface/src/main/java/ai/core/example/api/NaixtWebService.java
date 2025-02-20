package ai.core.example.api;

import ai.core.example.api.naixt.NaixtChatRequest;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface NaixtWebService {
    @PUT
    @Path("/naixt/chat")
    ChatResponse chat(NaixtChatRequest request);
}
