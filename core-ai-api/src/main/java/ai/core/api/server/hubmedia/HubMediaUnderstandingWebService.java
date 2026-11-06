package ai.core.api.server.hubmedia;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * Media understanding for user-level clients: the same Gemini video model the sandbox's
 * {@code understand_video} tool uses, addressed with a normal user credential.
 *
 * @author stephen
 */
public interface HubMediaUnderstandingWebService {
    @POST
    @Path("/api/hub/media/understand-video")
    HubMediaUnderstandVideoResponse understandVideo(HubMediaUnderstandVideoRequest request);
}
