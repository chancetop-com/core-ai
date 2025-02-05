package ai.core.litellm.completion;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface CompletionAJAXWebService {
    @POST
    @Path("/chat/completions")
    CreateCompletionAJAXResponse completions(CreateCompletionAJAXRequest request);

    @POST
    @Path("/chat/completions")
    CreateCompletionAJAXResponse imageCompletions(CreateImageCompletionAJAXRequest request);
}
