package ai.core.media.audio;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI-shaped {@code /audio/transcriptions} client. The same protocol covers OpenAI, Azure OpenAI
 * deployments and LiteLLM relays; Azure differs only in the URL shape (deployment in the path) and
 * the {@code api-key} header.
 *
 * @author stephen
 */
public class OpenAIAudioTranscriptionClient implements TranscriptionProvider {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final String OPENAI_FIELD = "model";

    /** Azure OpenAI: the deployment id supplies the model, the upstream rejects a body-level model field. */
    public static OpenAIAudioTranscriptionClient azure(String baseUrl, String apiKey, String apiVersion) {
        return new OpenAIAudioTranscriptionClient(baseUrl, apiKey, apiVersion == null || apiVersion.isBlank()
                ? "2024-10-21" : apiVersion.trim());
    }

    private final String baseUrl;
    private final String apiKey;
    private final String apiVersion;
    private final HTTPClient client;

    public OpenAIAudioTranscriptionClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    private OpenAIAudioTranscriptionClient(String baseUrl, String apiKey, String apiVersion) {
        this.baseUrl = stripTrailingSlashes(baseUrl);
        this.apiKey = apiKey;
        this.apiVersion = apiVersion;
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .timeout(REQUEST_TIMEOUT)
                .trustAll()
                .build();
    }

    @Override
    public TranscriptionResult transcribe(TranscriptionRequest request) {
        var format = responseFormat(request.model());
        var response = execute(request, format);
        if (response.statusCode == 400 && "verbose_json".equals(format) && mentionsResponseFormat(response.text())) {
            // newer transcription models accept json/text but reject verbose_json; the text is still enough
            response = execute(request, "json");
        }
        var body = response.text();
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new TranscriptionException("speech transcription failed: HTTP " + response.statusCode + ": " + body);
        }
        return parse(body);
    }

    private HTTPResponse execute(TranscriptionRequest request, String format) {
        var httpRequest = new HTTPRequest(HTTPMethod.POST, endpoint(request.model()));
        if (apiKey != null && !apiKey.isBlank()) {
            if (apiVersion != null) httpRequest.headers.put("api-key", apiKey);
            else httpRequest.headers.put("Authorization", "Bearer " + apiKey);
        }
        var boundary = "----coreai-audio-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        if (apiVersion == null) field(body, boundary, OPENAI_FIELD, request.model());
        field(body, boundary, "response_format", format);
        field(body, boundary, "language", primaryLanguage(request.language()));
        field(body, boundary, "prompt", request.prompt());
        file(body, boundary, request);
        write(body, "--" + boundary + "--\r\n");
        httpRequest.body(body.toByteArray(), ContentType.create("multipart/form-data; boundary=" + boundary, StandardCharsets.UTF_8));
        return client.execute(httpRequest);
    }

    private String endpoint(String model) {
        if (apiVersion == null) return baseUrl + "/audio/transcriptions";
        return resourceBase() + "/openai/deployments/" + URLEncoder.encode(model == null ? "" : model, StandardCharsets.UTF_8)
                + "/audio/transcriptions?api-version=" + URLEncoder.encode(apiVersion, StandardCharsets.UTF_8);
    }

    /** Azure provider baseUrl usually ends with {@code /openai/v1}; the deployment API lives under {@code /openai}. */
    private String resourceBase() {
        if (baseUrl.endsWith("/openai/v1")) return baseUrl.substring(0, baseUrl.length() - "/openai/v1".length());
        if (baseUrl.endsWith("/openai")) return baseUrl.substring(0, baseUrl.length() - "/openai".length());
        if (baseUrl.endsWith("/v1")) return baseUrl.substring(0, baseUrl.length() - "/v1".length());
        return baseUrl;
    }

    /**
     * Whisper reports segments, language and duration only in verbose_json. The gpt-4o-transcribe family
     * rejects verbose_json, so it stays on json and the response is text-only.
     */
    private String responseFormat(String model) {
        if (model == null) return "json";
        var value = model.toLowerCase(Locale.ROOT);
        return value.contains("whisper") ? "verbose_json" : "json";
    }

    private boolean mentionsResponseFormat(String body) {
        if (body == null) return false;
        var value = body.toLowerCase(Locale.ROOT);
        return value.contains("response_format") || value.contains("verbose_json");
    }

    private String primaryLanguage(String language) {
        if (language == null || language.isBlank()) return null;
        var value = language.trim();
        var separator = Math.max(value.indexOf('-'), value.indexOf('_'));
        return (separator > 0 ? value.substring(0, separator) : value).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private TranscriptionResult parse(String body) {
        if (body == null || body.isBlank()) return new TranscriptionResult("");
        var trimmed = body.trim();
        if (!trimmed.startsWith("{")) return new TranscriptionResult(trimmed);
        var root = (Map<String, Object>) JsonUtil.fromJson(Map.class, trimmed);
        var text = string(root.get("text"));
        var language = string(root.get("language"));
        var duration = number(root.get("duration"));
        var segments = new ArrayList<TranscriptSegment>();
        if (root.get("segments") instanceof List<?> items) {
            for (var item : items) {
                if (!(item instanceof Map<?, ?> segment)) continue;
                segments.add(new TranscriptSegment(string(segment.get("text")),
                        number(segment.get("start")) == null ? 0 : number(segment.get("start")),
                        number(segment.get("end")) == null ? 0 : number(segment.get("end"))));
            }
        }
        return new TranscriptionResult(text, language, duration, segments);
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        if (value == null || value.isBlank()) return;
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private void file(ByteArrayOutputStream body, String boundary, TranscriptionRequest request) {
        var contentType = request.contentType() == null || request.contentType().isBlank()
                ? "application/octet-stream" : request.contentType();
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName(contentType) + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        body.writeBytes(request.audio());
        write(body, "\r\n");
    }

    private String fileName(String contentType) {
        var value = contentType.toLowerCase(Locale.ROOT);
        if (value.contains("wav")) return "audio.wav";
        if (value.contains("mpeg") || value.contains("mp3")) return "audio.mp3";
        if (value.contains("ogg")) return "audio.ogg";
        if (value.contains("webm")) return "audio.webm";
        if (value.contains("mp4") || value.contains("m4a")) return "audio.m4a";
        if (value.contains("flac")) return "audio.flac";
        return "audio.bin";
    }

    private void write(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String stripTrailingSlashes(String value) {
        return value == null ? null : value.replaceAll("/+$", "");
    }
}
