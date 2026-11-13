package ai.core.server.speech;

import ai.core.media.GoogleAccessTokenProvider;
import ai.core.media.audio.TranscriptSegment;
import ai.core.media.audio.TranscriptionException;
import ai.core.media.audio.TranscriptionProvider;
import ai.core.media.audio.TranscriptionRequest;
import ai.core.media.audio.TranscriptionResult;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.web.exception.BadRequestException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Google Cloud Speech-to-Text V2. The provider supplies the GCP project and location and either a
 * service-account token or an API key; diarization is requested so every result can carry a speaker
 * label.
 *
 * @author stephen
 */
public class GoogleSpeechTranscriptionClient implements TranscriptionProvider {
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(3))
            .build();
    private static final String DEFAULT_BASE_URL = "https://speech.googleapis.com";
    private static final Map<String, String> LOCALES = Map.ofEntries(
            Map.entry("zh", "zh-CN"), Map.entry("en", "en-US"), Map.entry("ja", "ja-JP"),
            Map.entry("ko", "ko-KR"), Map.entry("fr", "fr-FR"), Map.entry("de", "de-DE"),
            Map.entry("es", "es-ES"), Map.entry("it", "it-IT"), Map.entry("pt", "pt-BR"),
            Map.entry("ru", "ru-RU"), Map.entry("ar", "ar-SA"), Map.entry("hi", "hi-IN"),
            Map.entry("th", "th-TH"), Map.entry("vi", "vi-VN"), Map.entry("id", "id-ID"),
            Map.entry("ms", "ms-MY"), Map.entry("tr", "tr-TR"), Map.entry("nl", "nl-NL"),
            Map.entry("pl", "pl-PL")
    );

    private final String baseUrl;
    private final String projectId;
    private final String location;
    private final String apiKey;
    private final GoogleAccessTokenProvider tokenProvider;
    private final int maxSpeakers;

    public GoogleSpeechTranscriptionClient(String baseUrl, String projectId, String location,
                                           String apiKey, GoogleAccessTokenProvider tokenProvider, int maxSpeakers) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl.trim());
        this.projectId = projectId == null ? null : projectId.trim();
        this.location = location == null || location.isBlank() ? "global" : location.trim();
        this.apiKey = apiKey;
        this.tokenProvider = tokenProvider;
        this.maxSpeakers = maxSpeakers < 1 ? 8 : maxSpeakers;
    }

    @Override
    public TranscriptionResult transcribe(TranscriptionRequest request) {
        if (projectId == null || projectId.isBlank()) {
            throw new BadRequestException("google-speech provider requires a GCP project id on the provider");
        }
        var url = baseUrl + "/v2/projects/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8)
                + "/locations/" + URLEncoder.encode(location, StandardCharsets.UTF_8)
                + "/recognizers/_:recognize";
        var httpRequest = new HTTPRequest(HTTPMethod.POST, url);
        httpRequest.headers.put("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            httpRequest.headers.put("x-goog-api-key", apiKey);
        } else if (tokenProvider != null) {
            httpRequest.headers.put("Authorization", "Bearer " + tokenProvider.accessToken());
        }
        httpRequest.body(JsonUtil.toJson(body(request)).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        var response = CLIENT.execute(httpRequest);
        var body = response.body == null ? "" : new String(response.body, StandardCharsets.UTF_8);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new TranscriptionException("google speech transcription failed: HTTP " + response.statusCode + ": " + body);
        }
        return parse(body, request.language());
    }

    private Map<String, Object> body(TranscriptionRequest request) {
        var config = new LinkedHashMap<String, Object>();
        config.put("autoDecodingConfig", Map.of());
        config.put("languageCodes", List.of(locale(request.language())));
        if (request.model() != null && !request.model().isBlank() && !"default".equalsIgnoreCase(request.model())) {
            config.put("model", request.model().trim());
        }
        var features = new LinkedHashMap<String, Object>();
        features.put("enableWordTimeOffsets", Boolean.TRUE);
        features.put("enableAutomaticPunctuation", Boolean.TRUE);
        features.put("diarizationConfig", Map.of("minSpeakerCount", 1, "maxSpeakerCount", maxSpeakers));
        config.put("features", features);

        var body = new LinkedHashMap<String, Object>();
        body.put("config", config);
        body.put("audio", Map.of("content", Base64.getEncoder().encodeToString(request.audio())));
        return body;
    }

    @SuppressWarnings("unchecked")
    TranscriptionResult parse(String json, String requestedLanguage) {
        var root = JsonUtil.toMap(json);
        var results = list(root.get("results"));
        var text = new StringBuilder();
        var segments = new ArrayList<TranscriptSegment>();
        String detectedLanguage = null;
        double duration = 0;
        for (var resultObject : results) {
            if (!(resultObject instanceof Map<?, ?> result)) continue;
            var alternatives = list(result.get("alternatives"));
            if (alternatives.isEmpty() || !(alternatives.getFirst() instanceof Map<?, ?> alternative)) continue;
            var transcript = string(alternative.get("transcript"));
            if (transcript.isBlank()) continue;
            if (detectedLanguage == null || detectedLanguage.isBlank()) {
                var resultLanguage = string(result.get("languageCode"));
                detectedLanguage = resultLanguage.isBlank() ? requestedLanguage : resultLanguage;
            }
            var words = list(alternative.get("words"));
            double start = 0;
            double end = seconds(result.get("resultEndOffset"));
            String speaker = null;
            if (!words.isEmpty()) {
                start = seconds(wordValue(words.getFirst(), "startOffset"));
                end = seconds(wordValue(words.getLast(), "endOffset"));
                speaker = speakerLabel(words.getFirst());
            }
            if (end < start) end = start;
            duration = Math.max(duration, end);
            segments.add(new TranscriptSegment(transcript, start, end, speaker));
            if (text.length() > 0 && !isCjk(detectedLanguage)) text.append(' ');
            text.append(transcript);
        }
        return new TranscriptionResult(text.toString(), detectedLanguage, duration == 0 ? null : duration, segments);
    }

    private String locale(String language) {
        if (language == null || language.isBlank() || "auto".equalsIgnoreCase(language)) return "en-US";
        var value = language.trim();
        if (value.contains("-")) return value;
        if (value.contains("_")) return value.replace('_', '-');
        return LOCALES.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }

    private boolean isCjk(String language) {
        if (language == null) return false;
        var value = language.toLowerCase(Locale.ROOT);
        return value.startsWith("zh") || value.startsWith("ja") || value.startsWith("ko");
    }

    private Object wordValue(Object value, String field) {
        return value instanceof Map<?, ?> word ? word.get(field) : null;
    }

    private String speakerLabel(Object value) {
        if (!(value instanceof Map<?, ?> word)) return null;
        var speaker = string(word.get("speakerLabel"));
        return speaker.isBlank() ? null : speaker;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String string(Object value) {
        if (value == null) return "";
        return String.valueOf(value);
    }

    private double seconds(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            var normalized = text.trim().toLowerCase(Locale.ROOT);
            if (normalized.endsWith("s")) normalized = normalized.substring(0, normalized.length() - 1);
            try {
                return Double.parseDouble(normalized);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
