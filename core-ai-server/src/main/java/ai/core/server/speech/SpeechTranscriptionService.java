package ai.core.server.speech;

import ai.core.api.server.speech.ListSpeechModelsResponse;
import ai.core.api.server.speech.SpeechModelView;
import ai.core.api.server.speech.SpeechTranscriptSegmentView;
import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.media.audio.AsrSpec;
import ai.core.media.audio.AudioProvider;
import ai.core.media.audio.TranscriptionException;
import ai.core.media.audio.TranscriptionRequest;
import ai.core.media.audio.TranscriptionResult;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewayTranscriptionProvider;
import ai.core.server.settings.SystemSettingsService;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Speech-to-text resolution shared by every client surface. A gateway model that declares
 * {@code audio.transcriptions} wins; when none is registered the server's Azure Speech credential is
 * the fallback. Both paths return the same normalized view, so the CLI and desktop do not care which
 * one served the request.
 *
 * @author stephen
 */
public class SpeechTranscriptionService {
    public static final String AZURE_SPEECH_MODEL = "azure-speech";
    private static final String AZURE_SPEECH_PROVIDER = "Azure Speech";
    private static final Map<String, String> AZURE_LOCALES = Map.ofEntries(
            Map.entry("zh", "zh-CN"), Map.entry("en", "en-US"), Map.entry("ja", "ja-JP"),
            Map.entry("ko", "ko-KR"), Map.entry("fr", "fr-FR"), Map.entry("de", "de-DE"),
            Map.entry("es", "es-ES"), Map.entry("it", "it-IT"), Map.entry("pt", "pt-BR"),
            Map.entry("ru", "ru-RU"), Map.entry("ar", "ar-SA"), Map.entry("hi", "hi-IN"),
            Map.entry("th", "th-TH"), Map.entry("vi", "vi-VN"), Map.entry("id", "id-ID"),
            Map.entry("ms", "ms-MY"), Map.entry("tr", "tr-TR"), Map.entry("nl", "nl-NL"),
            Map.entry("pl", "pl-PL")
    );

    @Inject
    GatewayRoutingEngine routingEngine;
    @Inject
    GatewayTranscriptionProvider gatewayTranscriptionProvider;
    @Inject
    SystemSettingsService settingsService;
    @Inject
    AudioProvider audioProvider;

    public ListSpeechModelsResponse models() {
        var response = new ListSpeechModelsResponse();
        var models = new ArrayList<SpeechModelView>();
        var seen = new HashSet<String>();
        for (var hint : routingEngine.explicitMediaModelHints(GatewayEndpointType.AUDIO_TRANSCRIPTION)) {
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
        response.defaultModel = models.isEmpty() ? null : models.getFirst().modelId;
        response.azureConfigured = azureConfigured;
        return response;
    }

    public SpeechTranscriptionView transcribe(byte[] audio, String contentType, String model, String language, String prompt) {
        if (audio == null || audio.length == 0) {
            throw new BadRequestException("audio is required", "BAD_REQUEST");
        }
        var resolvedModel = resolveModel(model);
        if (AZURE_SPEECH_MODEL.equals(resolvedModel)) {
            return azure(audio, contentType, language);
        }
        return gateway(resolvedModel, audio, contentType, language, prompt);
    }

    private String resolveModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            if (AZURE_SPEECH_MODEL.equals(requested.trim()) && !azureConfigured()) {
                throw new BadRequestException("Azure Speech is not configured on this server", "SPEECH_NOT_CONFIGURED");
            }
            return requested.trim();
        }
        var models = routingEngine.explicitMediaModelHints(GatewayEndpointType.AUDIO_TRANSCRIPTION);
        if (!models.isEmpty()) return models.getFirst().modelId();
        if (azureConfigured()) return AZURE_SPEECH_MODEL;
        throw new BadRequestException("speech transcription is not configured on this server: register a model with"
                + " the audio.transcriptions endpoint or set the Azure Speech key and region", "SPEECH_NOT_CONFIGURED");
    }

    private SpeechTranscriptionView gateway(String model, byte[] audio, String contentType, String language, String prompt) {
        try {
            var result = gatewayTranscriptionProvider.transcribe(
                    new TranscriptionRequest(model, audio, contentType, language, prompt));
            return view(result, model, modelProvider(model), language);
        } catch (TranscriptionException e) {
            // upstream 4xx is almost always caller-actionable (format/size/model) — keep it a 400
            throw new BadRequestException(e.getMessage(), "SPEECH_UPSTREAM_FAILED", e);
        }
    }

    private SpeechTranscriptionView azure(byte[] audio, String contentType, String language) {
        var locale = azureLocale(language);
        var result = audioProvider.transcribe(new AsrSpec(audio, contentType, locale));
        var segments = new ArrayList<SpeechTranscriptSegmentView>();
        Double duration = null;
        if (!result.words().isEmpty()) {
            var first = result.words().getFirst();
            var last = result.words().getLast();
            var segment = new SpeechTranscriptSegmentView();
            segment.text = result.text();
            segment.start = first.startSec();
            segment.end = last.endSec();
            segments.add(segment);
            duration = last.endSec();
        }
        var view = view(new TranscriptionResult(result.text(), locale, duration, List.of()), AZURE_SPEECH_MODEL,
                AZURE_SPEECH_PROVIDER, locale);
        view.segments = segments;
        return view;
    }

    private SpeechTranscriptionView view(TranscriptionResult result, String model, String provider, String requestedLanguage) {
        var view = new SpeechTranscriptionView();
        view.text = result.text();
        view.language = result.language() == null || result.language().isBlank()
                ? normalizedLanguage(requestedLanguage) : result.language();
        view.durationSeconds = result.durationSeconds();
        view.model = model;
        view.provider = provider;
        var segments = new ArrayList<SpeechTranscriptSegmentView>();
        for (var segment : result.segments()) {
            var item = new SpeechTranscriptSegmentView();
            item.text = segment.text();
            item.start = segment.startSec();
            item.end = segment.endSec();
            segments.add(item);
        }
        view.segments = segments;
        return view;
    }

    private String modelProvider(String model) {
        for (var hint : routingEngine.explicitMediaModelHints(GatewayEndpointType.AUDIO_TRANSCRIPTION)) {
            if (model.equals(hint.modelId())) return hint.providerName();
        }
        return null;
    }

    private String normalizedLanguage(String language) {
        if (language == null || language.isBlank() || "auto".equalsIgnoreCase(language)) return null;
        return language.trim();
    }

    private String azureLocale(String language) {
        var value = normalizedLanguage(language);
        if (value == null) return "zh-CN";
        if (value.contains("-")) return value;
        if (value.contains("_")) return value.replace('_', '-');
        var mapped = AZURE_LOCALES.get(value.toLowerCase(Locale.ROOT));
        return mapped == null ? value : mapped;
    }

    private boolean azureConfigured() {
        var key = settingsService.azureSpeechKey();
        var region = settingsService.azureSpeechRegion();
        return key != null && region != null && !key.isBlank() && !region.isBlank();
    }
}
