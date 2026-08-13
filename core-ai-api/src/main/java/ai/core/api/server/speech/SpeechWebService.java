package ai.core.api.server.speech;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface SpeechWebService {
    @GET
    @Path("/api/speech/token")
    SpeechTokenView getToken();
}
