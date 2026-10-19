package ai.core.llm.providers;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.media.MediaProvider;
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
import core.framework.http.HTTPResponse;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI/LiteLLM shaped media endpoint. The request bodies are built explicitly (instead of serialising the
 * request record) so the reference fields use the gateway's documented snake_case wire names and empty
 * reference lists are omitted. The HTTP clients are split by call shape: an image generation may block for
 * minutes, while a video status poll must return quickly.
 *
 * @author stephen
 */
public class LiteLLMMediaProvider implements MediaProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiteLLMMediaProvider.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final String COST_HEADER = "x-litellm-response-cost";

    protected static HTTPClient httpClient(Duration timeout) {
        return new PatchedHTTPClientBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .timeout(timeout)
                .trustAll()
                .build();
    }

    protected final String url;
    protected final String token;
    private final HTTPClient generationClient;
    private final HTTPClient controlClient;
    private final HTTPClient downloadClient;
    private final boolean acceptsImageReferences;

    public LiteLLMMediaProvider(String url, String token) {
        this(url, token, false);
    }

    protected LiteLLMMediaProvider(String url, String token, boolean acceptsImageReferences) {
        this(url, token, acceptsImageReferences, GENERATION_TIMEOUT, CONTROL_TIMEOUT, DOWNLOAD_TIMEOUT);
    }

    protected LiteLLMMediaProvider(String url, String token, boolean acceptsImageReferences,
                                   Duration generationTimeout, Duration controlTimeout, Duration downloadTimeout) {
        this.url = stripTrailingSlashes(url);
        this.token = token;
        this.acceptsImageReferences = acceptsImageReferences;
        this.generationClient = httpClient(generationTimeout);
        this.controlClient = httpClient(controlTimeout);
        this.downloadClient = httpClient(downloadTimeout);
    }

    /** A session-scoped copy that reuses this provider's clients and connection pools. */
    protected LiteLLMMediaProvider(LiteLLMMediaProvider source) {
        this.url = source.url;
        this.token = source.token;
        this.acceptsImageReferences = source.acceptsImageReferences;
        this.generationClient = source.generationClient;
        this.controlClient = source.controlClient;
        this.downloadClient = source.downloadClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        var reqBody = imageRequestBody(request);
        mergeProviderExtra(reqBody, request.providerExtra());

        var httpRequest = request(HTTPMethod.POST, url + "/images/generations");
        httpRequest.body(JsonUtil.toJson(reqBody).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        var httpResponse = execute(generationClient, httpRequest, "image generation");

        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, httpResponse.text());
        var images = images(responseMap);
        return new ImageGenerationResponse(images, usage(responseMap, httpResponse),
                string(responseMap, "interaction_id"), string(responseMap, "media_id"), notes(responseMap));
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        var reqBody = videoRequestBody(request);
        mergeProviderExtra(reqBody, request.providerExtra());

        var httpRequest = request(HTTPMethod.POST, url + "/videos");
        httpRequest.body(JsonUtil.toJson(reqBody).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        var httpResponse = execute(controlClient, httpRequest, "video generation");

        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, httpResponse.text());
        return new VideoGenerationResponse(string(responseMap, "id"), string(responseMap, "status"),
                longValue(responseMap, "created_at"), usage(responseMap, httpResponse), notes(responseMap));
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoStatusResponse getVideoStatus(String videoId) {
        var httpRequest = request(HTTPMethod.GET, url + "/videos/" + videoId);
        var httpResponse = execute(controlClient, httpRequest, "video status");

        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, httpResponse.text());
        var upstreamCost = doubleValue(responseMap, "upstream_cost_usd");
        return new VideoStatusResponse(
                string(responseMap, "id"), string(responseMap, "status"), intValue(responseMap, "progress"),
                string(responseMap, "error"), longValue(responseMap, "completed_at"),
                doubleValue(responseMap, "credits_consumed"),
                upstreamCost != null ? upstreamCost : responseCost(httpResponse),
                usage(responseMap, httpResponse));
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var httpRequest = request(HTTPMethod.GET, url + "/videos/" + videoId + "/content");
        return execute(downloadClient, httpRequest, "video download").body;
    }

    @Override
    public boolean acceptsImageReferences() {
        return acceptsImageReferences;
    }

    protected void setHeaders(HTTPRequest request) {
        request.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        if (!Strings.isBlank(token)) {
            request.headers.put("Authorization", "Bearer " + token);
        }
    }

    private HTTPRequest request(HTTPMethod method, String target) {
        var httpRequest = new HTTPRequest(method, target);
        setHeaders(httpRequest);
        return httpRequest;
    }

    private HTTPResponse execute(HTTPClient client, HTTPRequest request, String operation) {
        var httpResponse = client.execute(request);
        if (httpResponse.statusCode < 200 || httpResponse.statusCode >= 300) {
            throw new RuntimeException(operation + " failed: HTTP " + httpResponse.statusCode + ": "
                    + truncated(httpResponse.text()));
        }
        return httpResponse;
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
        if (request.inputImages() != null && !request.inputImages().isEmpty()) {
            var images = new ArrayList<Map<String, Object>>();
            for (var reference : request.inputImages()) {
                images.add(referenceBody(reference));
            }
            body.put("input_images", images);
        }
        if (request.mask() != null) body.put("mask", referenceBody(request.mask()));
        putIfNotNull(body, "previous_interaction_id", request.previousInteractionId());
        return body;
    }

    private Map<String, Object> videoRequestBody(VideoGenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfNotNull(body, "model", request.model());
        putIfNotNull(body, "prompt", request.prompt());
        putIfNotNull(body, "seconds", request.seconds());
        putIfNotNull(body, "size", request.size());
        if (request.inputReferences() != null && !request.inputReferences().isEmpty()) {
            var references = new ArrayList<Map<String, Object>>();
            for (var reference : request.inputReferences()) {
                references.add(referenceBody(reference));
            }
            body.put("input_references", references);
        }
        putIfNotNull(body, "previous_video_id", request.previousInteractionId());
        return body;
    }

    /** Explicit wire naming: a record serialiser would produce camelCase names the gateway does not read. */
    private Map<String, Object> referenceBody(MediaReference reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfNotNull(body, "url", reference.url());
        putIfNotNull(body, "b64Json", reference.b64Json());
        putIfNotNull(body, "media_id", reference.mediaId());
        putIfNotNull(body, "name", reference.name());
        if (reference.role() != null) body.put("role", reference.role().name().toLowerCase(Locale.ROOT));
        if (reference.modality() != null) body.put("modality", reference.modality().name().toLowerCase(Locale.ROOT));
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<ImageData> images(Map<String, Object> responseMap) {
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
        return images;
    }

    private List<String> notes(Map<String, Object> responseMap) {
        if (!(responseMap.get("notes") instanceof List<?> values)) return List.of();
        var notes = new ArrayList<String>();
        for (var value : values) {
            if (value != null) notes.add(String.valueOf(value));
        }
        return notes;
    }

    private void putIfNotNull(Map<String, Object> body, String key, Object value) {
        if (value != null) body.put(key, value);
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

    private Double doubleValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String string(Map<String, Object> map, String key) {
        return map.get(key) instanceof String value ? value : null;
    }

    /**
     * OpenAI/Azure image responses carry token details (input_tokens_details.text_tokens/image_tokens) that the
     * pricing catalog prices apart; a LiteLLM proxy may additionally report the settled cost in a header.
     */
    @SuppressWarnings("unchecked")
    private Usage usage(Map<String, Object> responseMap, HTTPResponse response) {
        var cost = responseCost(response);
        if (!(responseMap.get("usage") instanceof Map<?, ?> map)) {
            return cost == null ? null : new Usage(null, null, null, null, null, null, null, cost, null);
        }
        var usageMap = (Map<String, Object>) map;
        var details = usageMap.get("input_tokens_details") instanceof Map<?, ?> detailMap
                ? (Map<String, Object>) detailMap : Map.<String, Object>of();
        var upstreamCost = doubleValue(usageMap, "upstream_cost_usd");
        return new Usage(
                intValue(usageMap, "total_tokens"),
                intValue(usageMap, "image_count"),
                intValue(usageMap, "video_seconds"),
                intValue(usageMap, "input_tokens"),
                intValue(usageMap, "output_tokens"),
                intValue(details, "text_tokens"),
                intValue(details, "image_tokens"),
                upstreamCost != null ? upstreamCost : cost,
                intValue(usageMap, "output_video_tokens"));
    }

    private Double responseCost(HTTPResponse response) {
        if (response == null || response.headers == null) return null;
        var value = response.headers.get(COST_HEADER);
        if (value == null) {
            for (var entry : response.headers.entrySet()) {
                if (COST_HEADER.equalsIgnoreCase(entry.getKey())) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        if (value == null || value.isBlank()) return null;
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.debug("invalid x-litellm-response-cost header: {}", value);
            return null;
        }
    }

    private String truncated(String message) {
        if (message == null || message.length() <= 2000) return message;
        return message.substring(0, 2000) + "...";
    }

    private String stripTrailingSlashes(String value) {
        if (value == null) return null;
        return value.replaceAll("/+$", "");
    }
}
