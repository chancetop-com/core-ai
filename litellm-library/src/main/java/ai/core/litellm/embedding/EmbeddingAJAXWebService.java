package ai.core.litellm.embedding;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface EmbeddingAJAXWebService {
    @POST
    @Path("/embeddings")
    CreateEmbeddingAJAXResponse embedding(CreateEmbeddingAJAXRequest request);
}
