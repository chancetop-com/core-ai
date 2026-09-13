package ai.core.media;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Stephen
 */
public class GeminiImageMediaProvider implements MediaProvider {
    private final String baseUrl;
    private final String credential;
    private final String credentialHeader;
    private final HTTPClient client;

    public GeminiImageMediaProvider(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, "x-goog-api-key");
    }

    public GeminiImageMediaProvider(String baseUrl, String credential, String credentialHeader) {
        this.baseUrl = stripTrailingSlashes(baseUrl);
        this.credential = credential;
        this.credentialHeader = credentialHeader;
        client = new PatchedHTTPClientBuilder().connectTimeout(Duration.ofSeconds(10)).timeout(Duration.ofMinutes(5)).trustAll().build();
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("contents", List.of(Map.of("role", "user", "parts", parts(request))));
        body.put("generationConfig", generationConfig(request));
        if (request.providerExtra() != null && !request.providerExtra().isBlank()) mergeExtra(body, request.providerExtra());
        var httpRequest = new HTTPRequest(HTTPMethod.POST, baseUrl + "/models/" + request.model() + ":generateContent");
        httpRequest.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        if (credential != null && !credential.isBlank()) httpRequest.headers.put(credentialHeader, credential);
        httpRequest.body(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        var response = client.execute(httpRequest);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new RuntimeException("Gemini image request failed: HTTP " + response.statusCode + ": " + response.text());
        }
        return parseResponse(response.text());
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        throw new UnsupportedOperationException("Gemini generateContent protocol does not support video generation");
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        throw new UnsupportedOperationException("Gemini generateContent protocol does not support video generation");
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        throw new UnsupportedOperationException("Gemini generateContent protocol does not support video generation");
    }

    private List<Map<String, Object>> parts(ImageGenerationRequest request) {
        var parts = new ArrayList<Map<String, Object>>();
        if (request.prompt() != null && !request.prompt().isBlank()) parts.add(Map.of("text", request.prompt()));
        if (request.inputImages() != null) for (var image : request.inputImages()) parts.add(inlineData(image));
        if (parts.isEmpty()) throw new IllegalArgumentException("prompt or input image is required");
        return parts;
    }

    /**
     * Gemini image output is off unless responseModalities asks for it, and the frame shape only follows the
     * request through imageConfig; providerExtra still replaces the whole block when a caller needs to.
     */
    private Map<String, Object> generationConfig(ImageGenerationRequest request) {
        var config = new LinkedHashMap<String, Object>();
        config.put("responseModalities", List.of("TEXT", "IMAGE"));
        var aspectRatio = GeminiImageSize.aspectRatio(request.size());
        var imageSize = GeminiImageSize.imageSize(request.size());
        if (aspectRatio != null || imageSize != null) {
            var imageConfig = new LinkedHashMap<String, Object>();
            if (aspectRatio != null) imageConfig.put("aspectRatio", aspectRatio);
            if (imageSize != null) imageConfig.put("imageSize", imageSize);
            config.put("imageConfig", imageConfig);
        }
        return config;
    }

    private Map<String, Object> inlineData(MediaReference reference) {
        if (reference.b64Json() == null || reference.b64Json().isBlank()) {
            throw new IllegalArgumentException("Gemini image inputs require base64 data");
        }
        var value = reference.b64Json();
        var mimeType = "image/png";
        var data = value;
        if (value.startsWith("data:")) {
            var separator = value.indexOf(',');
            if (separator < 0) throw new IllegalArgumentException("invalid image data URL");
            var metadata = value.substring(5, separator);
            var semicolon = metadata.indexOf(';');
            mimeType = semicolon < 0 ? metadata : metadata.substring(0, semicolon);
            data = value.substring(separator + 1);
        }
        return Map.of("inlineData", Map.of("mimeType", mimeType, "data", data));
    }

    @SuppressWarnings("unchecked")
    private ImageGenerationResponse parseResponse(String json) {
        var response = (Map<String, Object>) JsonUtil.fromJson(Map.class, json);
        var images = new ArrayList<ImageData>();
        var candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates != null) {
            for (var candidate : candidates) {
                var image = outputImage(candidate);
                if (image != null) images.add(image);
            }
        }
        return new ImageGenerationResponse(images, usage(response));
    }

    /** A candidate can carry intermediate renders before the final one, which is the output the caller asked for. */
    @SuppressWarnings("unchecked")
    private ImageData outputImage(Map<String, Object> candidate) {
        var content = (Map<String, Object>) candidate.get("content");
        var parts = content == null ? null : (List<Map<String, Object>>) content.get("parts");
        if (parts == null) return null;
        ImageData image = null;
        for (var part : parts) {
            var inlineData = (Map<String, Object>) part.get("inlineData");
            if (inlineData != null && inlineData.get("data") instanceof String data) image = new ImageData(data, null, null);
        }
        return image;
    }

    @SuppressWarnings("unchecked")
    private Usage usage(Map<String, Object> response) {
        var metadata = (Map<String, Object>) response.get("usageMetadata");
        if (metadata == null) return null;
        var total = metadata.get("totalTokenCount");
        var input = metadata.get("promptTokenCount");
        var output = metadata.get("candidatesTokenCount");
        return new Usage(total instanceof Number number ? number.intValue() : null, null, null,
                input instanceof Number number ? number.intValue() : null,
                output instanceof Number number ? number.intValue() : null,
                null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private void mergeExtra(Map<String, Object> body, String extra) {
        body.putAll((Map<String, Object>) JsonUtil.fromJson(Map.class, extra));
    }

    private String stripTrailingSlashes(String value) {
        return value == null ? null : value.replaceAll("/+$", "");
    }
}
