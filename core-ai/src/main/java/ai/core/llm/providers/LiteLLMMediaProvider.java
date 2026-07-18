package ai.core.llm.providers;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.util.Strings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class LiteLLMMediaProvider implements MediaProvider {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final String url;
    private final String token;
    private final HTTPClient client;

    public LiteLLMMediaProvider(String url, String token) {
        this.url = stripTrailingSlashes(url);
        this.token = token;
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .timeout(TIMEOUT)
                .trustAll()
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        var reqBody = imageRequestBody(request);
        mergeProviderExtra(reqBody, request.providerExtra());

        var httpRequest = new HTTPRequest(HTTPMethod.POST, url + "/images/generations");
        setHeaders(httpRequest);
        httpRequest.body(JsonUtil.toJson(reqBody).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        var httpResponse = client.execute(httpRequest);
        if (httpResponse.statusCode < 200 || httpResponse.statusCode >= 300) {
            throw new RuntimeException("image generation failed: HTTP " + httpResponse.statusCode + ": " + httpResponse.text());
        }

        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, httpResponse.text());
        var dataList = (List<Map<String, Object>>) responseMap.get("data");
        var images = new ArrayList<ImageData>();
        if (dataList != null) {
            for (var data : dataList) {
                images.add(new ImageData(
                        (String) data.get("b64_json"),
                        (String) data.get("url"),
                        (String) data.get("revised_prompt")));
            }
        }
        var usageMap = (Map<String, Object>) responseMap.get("usage");
        var usage = usageMap == null ? null : new Usage(
                intValue(usageMap, "total_tokens"),
                intValue(usageMap, "image_count"),
                intValue(usageMap, "video_seconds"));
        return new ImageGenerationResponse(images, usage);
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        var reqBody = new LinkedHashMap<>(JsonUtil.toMap(request));
        mergeProviderExtra(reqBody, request.providerExtra());

        var httpRequest = new HTTPRequest(HTTPMethod.POST, url + "/videos");
        setHeaders(httpRequest);
        httpRequest.body(JsonUtil.toJson(reqBody).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        var httpResponse = client.execute(httpRequest);
        if (httpResponse.statusCode < 200 || httpResponse.statusCode >= 300) {
            throw new RuntimeException("video generation failed: HTTP " + httpResponse.statusCode + ": " + httpResponse.text());
        }

        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, httpResponse.text());
        var usageMap = (Map<String, Object>) responseMap.get("usage");
        var usage = usageMap == null ? null : new Usage(
                intValue(usageMap, "total_tokens"),
                intValue(usageMap, "image_count"),
                intValue(usageMap, "video_seconds"));
        return new VideoGenerationResponse(
                (String) responseMap.get("id"),
                (String) responseMap.get("status"),
                longValue(responseMap, "created_at"),
                usage);
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoStatusResponse getVideoStatus(String videoId) {
        var httpRequest = new HTTPRequest(HTTPMethod.GET, url + "/videos/" + videoId);
        setHeaders(httpRequest);

        var httpResponse = client.execute(httpRequest);
        if (httpResponse.statusCode < 200 || httpResponse.statusCode >= 300) {
            throw new RuntimeException("video status failed: HTTP " + httpResponse.statusCode + ": " + httpResponse.text());
        }

        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, httpResponse.text());
        return new VideoStatusResponse(
                (String) responseMap.get("id"),
                (String) responseMap.get("status"),
                intValue(responseMap, "progress"),
                (String) responseMap.get("error"),
                longValue(responseMap, "completed_at"));
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var httpRequest = new HTTPRequest(HTTPMethod.GET, url + "/videos/" + videoId + "/content");
        setHeaders(httpRequest);

        var httpResponse = client.execute(httpRequest);
        if (httpResponse.statusCode < 200 || httpResponse.statusCode >= 300) {
            throw new RuntimeException("video download failed: HTTP " + httpResponse.statusCode + ": " + httpResponse.text());
        }
        return httpResponse.body;
    }

    private Map<String, Object> imageRequestBody(ImageGenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfNotNull(body, "model", request.model());
        putIfNotNull(body, "prompt", request.prompt());
        putIfNotNull(body, "n", request.n());
        putIfNotNull(body, "size", request.size());
        putIfNotNull(body, "quality", request.quality());
        putIfNotNull(body, "output_format", request.outputFormat());
        putIfNotNull(body, "output_compression", request.outputCompression());
        putIfNotNull(body, "background", request.background());
        return body;
    }

    private void putIfNotNull(Map<String, Object> body, String key, Object value) {
        if (value != null) body.put(key, value);
    }

    private void setHeaders(HTTPRequest request) {
        request.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        if (!Strings.isBlank(token)) {
            request.headers.put("Authorization", "Bearer " + token);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeProviderExtra(Map<String, Object> body, String providerExtra) {
        if (providerExtra == null || providerExtra.isBlank()) return;
        try {
            var extra = (Map<String, Object>) JsonUtil.fromJson(Map.class, providerExtra);
            body.putAll(extra);
        } catch (Exception e) {
            throw new RuntimeException("invalid providerExtra JSON: " + e.getMessage(), e);
        }
    }

    private Integer intValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long longValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String stripTrailingSlashes(String url) {
        if (url == null) return null;
        return url.replaceAll("/+$", "");
    }
}
