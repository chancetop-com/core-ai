package ai.core.server.gateway;

import ai.core.media.audio.OpenAISpeechSynthesisProvider;
import ai.core.media.audio.SpeechSynthesisProvider;
import ai.core.media.audio.SpeechSynthesisRequest;
import ai.core.media.audio.SpeechSynthesisResult;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Routes a text-to-speech request to the gateway model that declares {@code audio.speech}. Google
 * Cloud Speech is transcription-only for now; TTS uses the OpenAI {@code /audio/speech} surface,
 * including Azure OpenAI deployments.
 *
 * @author stephen
 */
public class GatewaySpeechSynthesisProvider implements SpeechSynthesisProvider {
    private static final int MAX_CACHED_PROVIDERS = 32;
    private static final String DEFAULT_AZURE_API_VERSION = "2024-10-21";

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewaySecretProtector secretProtector;

    private final ConcurrentMap<String, SpeechSynthesisProvider> providers = new ConcurrentHashMap<>();

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        var route = routingEngine.route(request.model(), GatewayEndpointType.AUDIO_SPEECH);
        if (!hasText(route.upstreamModel())) {
            throw new BadRequestException("gateway speech model has no upstream model: " + route.model().modelId);
        }
        var rewritten = new SpeechSynthesisRequest(route.upstreamModel(), request.text(), request.voice(),
                request.language(), request.format(), request.speed(), request.instructions());
        return provider(route.provider()).synthesize(rewritten);
    }

    private SpeechSynthesisProvider provider(GatewayProviderConfig provider) {
        var key = provider.id + ":" + provider.updatedAt;
        var cached = providers.get(key);
        if (cached != null) return cached;
        if (providers.size() >= MAX_CACHED_PROVIDERS) providers.clear();
        return providers.computeIfAbsent(key, ignored -> create(provider));
    }

    private SpeechSynthesisProvider create(GatewayProviderConfig provider) {
        if (GatewaySpeechSupport.isGoogleSpeechProvider(provider)) {
            throw new BadRequestException("google-speech provider supports transcription only; register an"
                    + " OpenAI-compatible provider for text-to-speech");
        }
        if (!supportsSpeech(provider)) {
            throw new BadRequestException("gateway provider " + provider.name + " (media protocol "
                    + provider.mediaProtocol + ") does not support text-to-speech; use an OpenAI-compatible"
                    + " provider or set mediaProtocol=OPENAI_AUDIO");
        }
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        if ("azure".equals(provider.type)) {
            var version = hasText(provider.apiVersion) ? provider.apiVersion : DEFAULT_AZURE_API_VERSION;
            return OpenAISpeechSynthesisProvider.azure(provider.baseUrl, apiKey == null ? "" : apiKey, version);
        }
        return new OpenAISpeechSynthesisProvider(provider.baseUrl, apiKey == null ? "" : apiKey);
    }

    private boolean supportsSpeech(GatewayProviderConfig provider) {
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
