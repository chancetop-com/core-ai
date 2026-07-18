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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * @author Stephen
 */
public class OpenAIImageMediaProvider implements MediaProvider {
    private final String baseUrl;
    private final String apiKey;
    private final HTTPClient client;

    public OpenAIImageMediaProvider(String baseUrl, String apiKey) {
        this.baseUrl = stripTrailingSlashes(baseUrl);
        this.apiKey = apiKey;
        client = new PatchedHTTPClientBuilder().connectTimeout(Duration.ofSeconds(10)).timeout(Duration.ofMinutes(5)).trustAll().build();
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        var editing = request.inputImages() != null && !request.inputImages().isEmpty() || request.mask() != null;
        var httpRequest = editing ? editRequest(request) : generationRequest(request);
        var response = client.execute(httpRequest);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new RuntimeException("OpenAI image request failed: HTTP " + response.statusCode + ": " + response.text());
        }
        return parseResponse(response.text());
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        throw new UnsupportedOperationException("OpenAI image protocol does not support video generation");
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        throw new UnsupportedOperationException("OpenAI image protocol does not support video generation");
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        throw new UnsupportedOperationException("OpenAI image protocol does not support video generation");
    }

    private HTTPRequest generationRequest(ImageGenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "model", request.model());
        put(body, "prompt", request.prompt());
        put(body, "n", request.n());
        put(body, "size", request.size());
        put(body, "quality", normalizedQuality(request.quality()));
        put(body, "output_format", request.outputFormat());
        put(body, "output_compression", request.outputCompression());
        put(body, "background", request.background());
        mergeExtra(body, request.providerExtra());
        var httpRequest = request("/images/generations");
        httpRequest.body(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        return httpRequest;
    }

    private HTTPRequest editRequest(ImageGenerationRequest request) {
        var boundary = "----coreai-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        field(body, boundary, "model", request.model());
        field(body, boundary, "prompt", request.prompt());
        field(body, boundary, "n", request.n());
        field(body, boundary, "size", request.size());
        field(body, boundary, "quality", normalizedQuality(request.quality()));
        field(body, boundary, "output_format", request.outputFormat());
        field(body, boundary, "output_compression", request.outputCompression());
        field(body, boundary, "background", request.background());
        if (request.inputImages() != null) {
            for (var image : request.inputImages()) file(body, boundary, "image[]", image);
        }
        if (request.mask() != null) file(body, boundary, "mask", request.mask());
        write(body, "--" + boundary + "--\r\n");
        var httpRequest = request("/images/edits");
        httpRequest.headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        httpRequest.body(body.toByteArray(), ContentType.create("multipart/form-data; boundary=" + boundary, StandardCharsets.UTF_8));
        return httpRequest;
    }

    @SuppressWarnings("unchecked")
    private ImageGenerationResponse parseResponse(String json) {
        var response = (Map<String, Object>) JsonUtil.fromJson(Map.class, json);
        var data = (List<Map<String, Object>>) response.get("data");
        var images = new ArrayList<ImageData>();
        if (data != null) for (var item : data) images.add(new ImageData((String) item.get("b64_json"), (String) item.get("url"), (String) item.get("revised_prompt")));
        var usageMap = (Map<String, Object>) response.get("usage");
        var usage = usageMap == null ? null : new Usage(intValue(usageMap, "total_tokens"), intValue(usageMap, "image_count"), null);
        return new ImageGenerationResponse(images, usage);
    }

    @SuppressWarnings("unchecked")
    private void mergeExtra(Map<String, Object> body, String extra) {
        if (extra == null || extra.isBlank()) return;
        body.putAll((Map<String, Object>) JsonUtil.fromJson(Map.class, extra));
    }

    private HTTPRequest request(String path) {
        var request = new HTTPRequest(HTTPMethod.POST, baseUrl + path);
        if (apiKey != null && !apiKey.isBlank()) request.headers.put("Authorization", "Bearer " + apiKey);
        return request;
    }

    private void field(ByteArrayOutputStream body, String boundary, String name, Object value) {
        if (value == null) return;
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private void file(ByteArrayOutputStream body, String boundary, String name, MediaReference reference) {
        var content = bytes(reference);
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n");
        body.writeBytes(content);
        write(body, "\r\n");
    }

    private byte[] bytes(MediaReference reference) {
        if (reference.b64Json() != null && !reference.b64Json().isBlank()) return Base64.getDecoder().decode(dataPart(reference.b64Json()));
        throw new IllegalArgumentException("OpenAI image edits require input images as base64 data");
    }

    private String dataPart(String value) {
        var comma = value.indexOf(',');
        return value.startsWith("data:") && comma >= 0 ? value.substring(comma + 1) : value;
    }

    private String normalizedQuality(String quality) {
        if (quality == null || quality.isBlank()) return null;
        return switch (quality.toLowerCase(Locale.ROOT)) {
            case "standard" -> "medium";
            case "hd" -> "high";
            default -> quality;
        };
    }

    private void put(Map<String, Object> body, String key, Object value) {
        if (value != null) body.put(key, value);
    }

    private Integer intValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private void write(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String stripTrailingSlashes(String value) {
        return value == null ? null : value.replaceAll("/+$", "");
    }
}
