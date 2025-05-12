package ai.core;

import ai.core.litellm.LiteLLMService;
import ai.core.litellm.authorization.AuthorizationInterceptor;
import ai.core.litellm.completion.CompletionAJAXWebService;
import ai.core.litellm.embedding.EmbeddingAJAXWebService;
import ai.core.litellm.image.ImageAJAXWebService;
import ai.core.litellm.model.ModelAJAXWebService;
import core.framework.http.HTTPClient;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class LiteLLMModule extends Module {
    @Override
    protected void initialize() {
        bindAPIClients(requiredProperty("litellm.api.base"), property("litellm.api.key").orElse(""));
        bindServices();
    }

    private void bindServices() {
        bind(LiteLLMService.class);
    }

    private void bindAPIClients(String server, String token) {
        var interceptor = bind(new AuthorizationInterceptor(token));
        var client = HTTPClient.builder().maxRetries(2).timeout(Duration.ofMinutes(1)).trustAll().build();
        api().client(CompletionAJAXWebService.class, server, client).intercept(interceptor);
        api().client(ModelAJAXWebService.class, server, client).intercept(interceptor);
        api().client(EmbeddingAJAXWebService.class, server, client).intercept(interceptor);
        api().client(ImageAJAXWebService.class, server, client).intercept(interceptor);
    }
}
