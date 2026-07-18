package ai.core.server.gateway;

import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.GeminiImageMediaProvider;
import ai.core.media.GoogleAccessTokenProvider;
import ai.core.media.MediaProvider;
import ai.core.media.VertexGeminiImageMediaProvider;
import ai.core.media.OpenAIImageMediaProvider;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;

/**
 * @author Stephen
 */
public class MediaProviderAdapterFactory {
    public MediaProvider create(GatewayProviderConfig provider, String apiKey) {
        return create(provider, apiKey, null);
    }

    public MediaProvider create(GatewayProviderConfig provider, String apiKey, String googleCredentialsJson) {
        var protocol = protocol(provider);
        return switch (protocol) {
            case "OPENAI_IMAGES" -> new OpenAIImageMediaProvider(provider.baseUrl, apiKey);
            case "OPENAI_COMPATIBLE" -> new LiteLLMMediaProvider(provider.baseUrl, apiKey);
            case "GEMINI_GENERATE_CONTENT" -> new GeminiImageMediaProvider(provider.baseUrl, apiKey);
            case "VERTEX_GEMINI_GENERATE_CONTENT" -> vertexProvider(provider, googleCredentialsJson);
            default -> throw new BadRequestException("unsupported media protocol: " + protocol);
        };
    }

    private MediaProvider vertexProvider(GatewayProviderConfig provider, String googleCredentialsJson) {
        if ("GOOGLE_SERVICE_ACCOUNT_JSON".equals(provider.mediaAuthType)
                && (googleCredentialsJson == null || googleCredentialsJson.isBlank())) {
            throw new BadRequestException("service account JSON is required for Google service account authentication");
        }
        return new VertexGeminiImageMediaProvider(provider.baseUrl, provider.vertexProjectId, provider.vertexLocation,
                new GoogleAccessTokenProvider("GOOGLE_SERVICE_ACCOUNT_JSON".equals(provider.mediaAuthType) ? googleCredentialsJson : null));
    }

    static String protocol(GatewayProviderConfig provider) {
        if (provider.mediaProtocol != null && !provider.mediaProtocol.isBlank()) return provider.mediaProtocol;
        return switch (provider.type) {
            case "openai" -> "OPENAI_IMAGES";
            case "litellm", "openai-compatible" -> "OPENAI_COMPATIBLE";
            default -> throw new BadRequestException("mediaProtocol is required for provider type: " + provider.type);
        };
    }
}
