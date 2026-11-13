package ai.core.server.gateway;

import ai.core.media.GoogleAccessTokenProvider;
import ai.core.media.audio.OpenAIAudioTranscriptionClient;
import ai.core.media.audio.TranscriptionProvider;
import ai.core.media.audio.TranscriptionRequest;
import ai.core.media.audio.TranscriptionResult;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.speech.GoogleSpeechTranscriptionClient;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Routes a transcription request to the gateway model that declares the {@code audio.transcriptions}
 * endpoint and talks OpenAI's multipart protocol to it. Providers configured for chat/image keep working:
 * the same base URL and API key serve audio when the provider is an OpenAI-family endpoint.
 *
 * @author stephen
 */
public class GatewayTranscriptionProvider implements TranscriptionProvider {
    private static final int MAX_CACHED_PROVIDERS = 32;

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewaySecretProtector secretProtector;

    private final ConcurrentMap<String, TranscriptionProvider> providers = new ConcurrentHashMap<>();

    @Override
    public TranscriptionResult transcribe(TranscriptionRequest request) {
        var route = routingEngine.route(request.model(), GatewayEndpointType.AUDIO_TRANSCRIPTION);
        if (!hasText(route.upstreamModel())) {
            throw new BadRequestException("gateway transcription model has no upstream model: " + route.model().modelId);
        }
        var rewritten = new TranscriptionRequest(route.upstreamModel(), request.audio(), request.contentType(),
                request.language(), request.prompt());
        return provider(route.provider()).transcribe(rewritten);
    }

    private TranscriptionProvider provider(GatewayProviderConfig provider) {
        var key = provider.id + ":" + provider.updatedAt;
        var cached = providers.get(key);
        if (cached != null) return cached;
        if (providers.size() >= MAX_CACHED_PROVIDERS) providers.clear();
        return providers.computeIfAbsent(key, ignored -> create(provider));
    }

    private TranscriptionProvider create(GatewayProviderConfig provider) {
        if (!supportsAudio(provider)) {
            throw new BadRequestException("gateway provider " + provider.name + " (media protocol "
                    + provider.mediaProtocol + ") does not support audio transcription; use an OpenAI-compatible"
                    + " provider, set mediaProtocol=OPENAI_AUDIO, or use a google-speech provider");
        }
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        if (GatewaySpeechSupport.isGoogleSpeechProvider(provider)) {
            return new GoogleSpeechTranscriptionClient(provider.baseUrl, provider.vertexProjectId, provider.vertexLocation,
                    apiKey, googleTokenProvider(provider), 8);
        }
        if ("azure".equals(provider.type)) {
            return OpenAIAudioTranscriptionClient.azure(provider.baseUrl, apiKey == null ? "" : apiKey, provider.apiVersion);
        }
        return new OpenAIAudioTranscriptionClient(provider.baseUrl, apiKey == null ? "" : apiKey);
    }

    private GoogleAccessTokenProvider googleTokenProvider(GatewayProviderConfig provider) {
        if ("API_KEY".equals(provider.mediaAuthType)) return null;
        var credentials = secretProtector.unprotect(provider.googleCredentialsEncrypted);
        if ("GOOGLE_SERVICE_ACCOUNT_JSON".equals(provider.mediaAuthType) && (credentials == null || credentials.isBlank())) {
            throw new BadRequestException("service account JSON is required for Google service account authentication");
        }
        return new GoogleAccessTokenProvider("GOOGLE_SERVICE_ACCOUNT_JSON".equals(provider.mediaAuthType) ? credentials : null);
    }

    /** Image/chat protocols are accepted too: the upstream audio route is the same OpenAI surface. */
    private boolean supportsAudio(GatewayProviderConfig provider) {
        if (GatewaySpeechSupport.isGoogleSpeechProvider(provider)) return true;
        var protocol = provider.mediaProtocol == null ? null : provider.mediaProtocol.trim().toUpperCase(Locale.ROOT);
        if ("OPENAI_AUDIO".equals(protocol)) return true;
        if (protocol != null && !protocol.isBlank()
                && !"OPENAI_IMAGES".equals(protocol) && !"OPENAI_COMPATIBLE".equals(protocol)) {
            return false;
        }
        return switch (provider.type) {
            case "openai", "azure", "litellm", "openai-compatible", "openrouter", "deepseek", "qwen" -> true;
            default -> false;
        };
    }
}
