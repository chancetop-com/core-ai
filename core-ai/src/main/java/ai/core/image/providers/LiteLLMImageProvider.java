package ai.core.image.providers;

import ai.core.image.ImageProvider;
import ai.core.image.providers.inner.GenerateImageRequest;
import ai.core.image.providers.inner.GenerateImageResponse;
import ai.core.litellm.LiteLLMService;
import ai.core.litellm.image.CreateImageAJAXRequest;
import core.framework.inject.Inject;


/**
 * @author stephen
 */
public class LiteLLMImageProvider implements ImageProvider {
    @Inject
    LiteLLMService liteLLMService;

    @Override
    public GenerateImageResponse generateImage(GenerateImageRequest request) {
        var rsp = liteLLMService.generateImage(toGenerateApiRequest(request));
        return new GenerateImageResponse(rsp.generatedDatas.getFirst().revisedPrompt, rsp.generatedDatas.getFirst().url);
    }

    private CreateImageAJAXRequest toGenerateApiRequest(GenerateImageRequest request) {
        var apiReq = new CreateImageAJAXRequest();
        apiReq.prompt = request.prompt();
        return apiReq;
    }
}
