package ai.core.litellm;

import ai.core.litellm.completion.CompletionAJAXWebService;
import ai.core.litellm.completion.CreateCompletionAJAXRequest;
import ai.core.litellm.completion.CreateCompletionAJAXResponse;
import ai.core.litellm.completion.CreateImageCompletionAJAXRequest;
import ai.core.litellm.embedding.CreateEmbeddingAJAXRequest;
import ai.core.litellm.embedding.CreateEmbeddingAJAXResponse;
import ai.core.litellm.embedding.EmbeddingAJAXWebService;
import ai.core.litellm.image.CreateImageAJAXRequest;
import ai.core.litellm.image.CreateImageAJAXResponse;
import ai.core.litellm.image.ImageAJAXWebService;
import ai.core.litellm.model.ModelAJAXWebService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class LiteLLMService {
    @Inject
    ModelAJAXWebService modelAJAXWebService;
    @Inject
    EmbeddingAJAXWebService embeddingAJAXWebService;
    @Inject
    CompletionAJAXWebService completionAJAXWebService;
    @Inject
    ImageAJAXWebService imageAJAXWebService;

    public CreateCompletionAJAXResponse completion(CreateCompletionAJAXRequest request) {
        return completionAJAXWebService.completions(request);
    }

    public CreateCompletionAJAXResponse imageCompletion(CreateImageCompletionAJAXRequest request) {
        return completionAJAXWebService.imageCompletions(request);
    }

    public CreateEmbeddingAJAXResponse embedding(CreateEmbeddingAJAXRequest request) {
        return embeddingAJAXWebService.embedding(request);
    }

    public CreateImageAJAXResponse generateImage(CreateImageAJAXRequest request) {
        return imageAJAXWebService.generate(request);
    }
}
