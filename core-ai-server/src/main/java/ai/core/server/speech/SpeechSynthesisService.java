package ai.core.server.speech;

import ai.core.api.server.speech.ListSpeechSynthesisModelsResponse;
import ai.core.api.server.speech.SpeechModelView;
import ai.core.api.server.speech.SynthesizeSpeechRequest;
import ai.core.media.audio.AudioProvider;
import ai.core.media.audio.SpeechSynthesisException;
import ai.core.media.audio.SpeechSynthesisRequest;
import ai.core.media.audio.TtsSpec;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewaySpeechSynthesisProvider;
import ai.core.server.settings.SystemSettingsService;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Text-to-speech resolution shared by API and agent surfaces. The default model comes from system
 * settings, then the first enabled {@code audio.speech} model, then the Azure Speech credential.
 *
 * @author stephen
 */
public class SpeechSynthesisService {
    public static final String AZURE_SPEECH_MODEL = "azure-speech";
    private static final String AZURE_SPEECH_PROVIDER = "Azure Speech";

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewaySpeechSynthesisProvider gatewaySpeechSynthesisProvider;
    @Inject
    SystemSettingsService settingsService;
    @Inject
    AudioProvider audioProvider;

    public ListSpeechSynthesisModelsResponse models() {
        var response = new ListSpeechSynthesisModelsResponse();
        var models = new ArrayList<SpeechModelView>();
        var seen = new HashSet<String>();
        for (var hint : routingEngine.explicitMediaModelHints(GatewayEndpointType.AUDIO_SPEECH)) {
            if (hint.modelId() == null || !seen.add(hint.modelId())) continue;
            var view = new SpeechModelView();
            view.modelId = hint.modelId();
            view.displayName = hint.displayName();
            view.providerName = hint.providerName();
            view.upstreamModel = hint.upstreamModel();
            models.add(view);
        }
        var azureConfigured = azureConfigured();
        if (azureConfigured) {
            var view = new SpeechModelView();
            view.modelId = AZURE_SPEECH_MODEL;
            view.displayName = "Azure Speech";
            view.providerName = AZURE_SPEECH_PROVIDER;
            models.add(view);
        }
        response.models = models;
        response.azureConfigured = azureConfigured;
        response.defaultModel = defaultModel(models);
        return response;
    }

    public SpeechSynthesis synthesize(SynthesizeSpeechRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            throw new BadRequestException("text is required", "BAD_REQUEST");
        }
        var model = resolveModel(request.model);
        if (AZURE_SPEECH_MODEL.equals(model)) {
            var result = audioProvider.synthesize(new TtsSpec(request.text, request.voice, null, null,
                    request.speed, null, normalizedLanguage(request.language)));
            return new SpeechSynthesis(result.audio(), result.contentType(), AZURE_SPEECH_MODEL, AZURE_SPEECH_PROVIDER, request.voice);
        }
        try {
            var result = gatewaySpeechSynthesisProvider.synthesize(new SpeechSynthesisRequest(model, request.text,
                    request.voice, request.language, request.format, request.speed, request.instructions));
            return new SpeechSynthesis(result.audio(), result.contentType(), model, modelProvider(model), result.voice());
        } catch (SpeechSynthesisException e) {
            throw new BadRequestException(e.getMessage(), "SPEECH_UPSTREAM_FAILED", e);
        }
    }

    private String resolveModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            var model = requested.trim();
            if (AZURE_SPEECH_MODEL.equals(model) && !azureConfigured()) {
                throw new BadRequestException("Azure Speech is not configured on this server", "SPEECH_NOT_CONFIGURED");
            }
            return model;
        }
        var configured = settingsService.speechSynthesisModel();
        if (configured != null && !configured.isBlank()) return configured;
        var hints = routingEngine.explicitMediaModelHints(GatewayEndpointType.AUDIO_SPEECH);
        if (!hints.isEmpty()) return hints.getFirst().modelId();
        if (azureConfigured()) return AZURE_SPEECH_MODEL;
        throw new BadRequestException("speech synthesis is not configured on this server: register a model with"
                + " the audio.speech endpoint or set the Azure Speech key and region", "SPEECH_NOT_CONFIGURED");
    }

    private String defaultModel(List<SpeechModelView> models) {
        if (models.isEmpty()) return null;
        var configured = settingsService.speechSynthesisModel();
        if (configured != null && models.stream().anyMatch(model -> configured.equals(model.modelId))) return configured;
        return models.getFirst().modelId;
    }

    private String modelProvider(String model) {
        for (var hint : routingEngine.explicitMediaModelHints(GatewayEndpointType.AUDIO_SPEECH)) {
            if (model.equals(hint.modelId())) return hint.providerName();
        }
        return null;
    }

    private String normalizedLanguage(String language) {
        if (language == null || language.isBlank() || "auto".equalsIgnoreCase(language)) return null;
        return language.trim();
    }

    private boolean azureConfigured() {
        var key = settingsService.azureSpeechKey();
        var region = settingsService.azureSpeechRegion();
        return key != null && region != null && !key.isBlank() && !region.isBlank();
    }

    public record SpeechSynthesis(byte[] audio, String contentType, String model, String provider, String voice) {
    }
}
