package ai.core.server.gateway;

import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.GeminiImageMediaProvider;
import ai.core.media.OpenAIImageMediaProvider;
import ai.core.media.VertexGeminiImageMediaProvider;
import ai.core.media.VertexGeminiOmniMediaProvider;
import ai.core.server.domain.GatewayProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MediaProviderAdapterFactoryTest {
    private final MediaProviderAdapterFactory factory = new MediaProviderAdapterFactory();

    @Test
    void createsOpenAIImagesAdapterWhenProtocolIsConfigured() {
        var provider = provider("openai", "OPENAI_IMAGES");

        assertInstanceOf(OpenAIImageMediaProvider.class, factory.create(provider, "key"));
    }

    @Test
    void createsGeminiAdapterWhenProtocolIsConfigured() {
        var provider = provider("gemini", "GEMINI_GENERATE_CONTENT");

        assertInstanceOf(GeminiImageMediaProvider.class, factory.create(provider, "key"));
    }

    @Test
    void createsVertexAdapterWithApplicationDefaultCredentials() {
        var provider = provider("gemini", "VERTEX_GEMINI_GENERATE_CONTENT");
        provider.mediaAuthType = "GOOGLE_APPLICATION_DEFAULT_CREDENTIALS";
        provider.vertexProjectId = "project";
        provider.vertexLocation = "us-central1";

        assertInstanceOf(VertexGeminiImageMediaProvider.class, factory.create(provider, ""));
    }

    @Test
    void createsVertexOmniAdapterWithApplicationDefaultCredentials() {
        var provider = provider("gemini", "VERTEX_GEMINI_INTERACTIONS");
        provider.mediaAuthType = "GOOGLE_APPLICATION_DEFAULT_CREDENTIALS";
        provider.vertexProjectId = "project";
        provider.vertexLocation = "global";

        assertInstanceOf(VertexGeminiOmniMediaProvider.class, factory.create(provider, ""));
    }

    @Test
    void preservesOpenAICompatibleAdapterForLegacyProvider() {
        var provider = provider("litellm", null);

        assertInstanceOf(LiteLLMMediaProvider.class, factory.create(provider, "key"));
    }

    private GatewayProviderConfig provider(String type, String mediaProtocol) {
        var provider = new GatewayProviderConfig();
        provider.type = type;
        provider.baseUrl = "https://example.com/v1";
        provider.mediaProtocol = mediaProtocol;
        return provider;
    }
}
