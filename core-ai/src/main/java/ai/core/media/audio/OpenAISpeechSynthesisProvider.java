package ai.core.media.audio;

import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible {@code /audio/speech} client. Azure OpenAI uses its deployment path and
 * {@code api-key} header; every other OpenAI-compatible provider uses the bearer token.
 *
 * @author stephen
 */
public class OpenAISpeechSynthesisProvider implements SpeechSynthesisProvider {
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(2))
            .build();
    private static final String DEFAULT_VOICE = "alloy";
    private static final String DEFAULT_FORMAT = "mp3";

    public static OpenAISpeechSynthesisProvider azure(String baseUrl, String apiKey, String apiVersion) {
        return new OpenAISpeechSynthesisProvider(baseUrl, apiKey, apiVersion);
    }

    private final String baseUrl;
    private final String apiKey;
    private final String apiVersion;

    public OpenAISpeechSynthesisProvider(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    public OpenAISpeechSynthesisProvider(String baseUrl, String apiKey, String apiVersion) {
        this.baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
        this.apiKey = apiKey;
        this.apiVersion = apiVersion;
    }

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        var voice = request.voice() == null || request.voice().isBlank() ? DEFAULT_VOICE : request.voice().trim();
        var format = request.format() == null || request.format().isBlank() ? DEFAULT_FORMAT : request.format().trim().toLowerCase(Locale.ROOT);
        var httpRequest = new HTTPRequest(HTTPMethod.POST, endpoint(request.model()));
        if (apiVersion != null) {
            if (apiKey != null && !apiKey.isBlank()) httpRequest.headers.put("api-key", apiKey);
        } else if (apiKey != null && !apiKey.isBlank()) {
            httpRequest.headers.put("Authorization", "Bearer " + apiKey);
        }
        httpRequest.body(JsonUtil.toJson(body(request, voice, format)).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        var response = CLIENT.execute(httpRequest);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new SpeechSynthesisException("speech synthesis failed: HTTP " + response.statusCode + ": "
                    + new String(response.body, StandardCharsets.UTF_8));
        }
        return new SpeechSynthesisResult(response.body, contentType(format), voice);
    }

    private Map<String, Object> body(SpeechSynthesisRequest request, String voice, String format) {
        var body = new LinkedHashMap<String, Object>();
        if (apiVersion == null && request.model() != null && !request.model().isBlank()) body.put("model", request.model());
        body.put("input", request.text());
        body.put("voice", voice);
        body.put("response_format", format);
        if (request.speed() != null) body.put("speed", request.speed());
        if (request.instructions() != null && !request.instructions().isBlank()) body.put("instructions", request.instructions());
        return body;
    }

    private String endpoint(String model) {
        if (apiVersion == null) return baseUrl + "/audio/speech";
        var deployment = URLEncoder.encode(model == null ? "" : model, StandardCharsets.UTF_8);
        return resourceBase() + "/openai/deployments/" + deployment + "/audio/speech?api-version="
                + URLEncoder.encode(apiVersion, StandardCharsets.UTF_8);
    }

    private String resourceBase() {
        if (baseUrl.endsWith("/openai/v1")) return baseUrl.substring(0, baseUrl.length() - "/openai/v1".length());
        if (baseUrl.endsWith("/openai")) return baseUrl.substring(0, baseUrl.length() - "/openai".length());
        if (baseUrl.endsWith("/v1")) return baseUrl.substring(0, baseUrl.length() - "/v1".length());
        return baseUrl;
    }

    private String contentType(String format) {
        return switch (format) {
            case "wav" -> "audio/wav";
            case "opus" -> "audio/ogg";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/L16";
            default -> "audio/mpeg";
        };
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
