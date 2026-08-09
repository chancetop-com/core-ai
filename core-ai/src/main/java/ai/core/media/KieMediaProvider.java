package ai.core.media;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * @author Stephen
 */
public class KieMediaProvider implements MediaProvider {
    private static final String DEFAULT_MARKET_URL = "https://api.kie.ai";
    private static final String DEFAULT_UPLOAD_URL = "https://kieai.redpandaai.co";
    private static final String CREATE_TASK_PATH = "/api/v1/jobs/createTask";
    private static final String RECORD_INFO_PATH = "/api/v1/jobs/recordInfo";
    private static final String FILE_BASE64_UPLOAD_PATH = "/api/file-base64-upload";

    private final String createTaskUrl;
    private final String recordInfoUrl;
    private final String uploadUrl;
    private final String token;
    private final Map<String, Object> defaultInputParams;
    private final HTTPClient client;

    public KieMediaProvider(String baseUrl, String token) {
        this(baseUrl, DEFAULT_UPLOAD_URL, token, null);
    }

    public KieMediaProvider(String baseUrl, String token, String requestExtraBody) {
        this(baseUrl, DEFAULT_UPLOAD_URL, token, requestExtraBody);
    }

    public KieMediaProvider(String baseUrl, String uploadBaseUrl, String token, String requestExtraBody) {
        this.createTaskUrl = rootUrl(baseUrl) + CREATE_TASK_PATH;
        this.recordInfoUrl = rootUrl(baseUrl) + RECORD_INFO_PATH;
        this.uploadUrl = rootUrl(uploadBaseUrl) + FILE_BASE64_UPLOAD_PATH;
        this.token = token;
        this.defaultInputParams = defaultInputParams(requestExtraBody);
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofMinutes(5))
                .trustAll()
                .build();
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        throw new UnsupportedOperationException("KIE image generation is not supported by this media provider");
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        if (request.model() == null || request.model().isBlank()) throw new IllegalArgumentException("video model is required");
        if (request.prompt() == null || request.prompt().isBlank()) throw new IllegalArgumentException("video prompt is required");

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        var input = input(request);
        body.put("input", input);
        mergeProviderExtra(body, input, request.providerExtra());

        var response = execute(HTTPMethod.POST, createTaskUrl, body, "video generation");
        var taskId = stringValue(taskData(response), "taskId");
        if (taskId == null || taskId.isBlank()) throw new IllegalStateException("KIE video task response is missing taskId");
        return new VideoGenerationResponse(taskId, "pending", null, null);
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        var task = taskData(execute(HTTPMethod.GET, recordInfoUrl(videoId), null, "video status"));
        var state = stringValue(task, "state");
        return new VideoStatusResponse(videoId, normalizeStatus(state), intValue(task, "progress"), stringValue(task, "failMsg"), null);
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var status = getVideoStatus(videoId);
        if (!"completed".equals(status.status())) {
            throw new IllegalStateException("KIE video is not completed, state=" + status.status());
        }
        var resultUrl = firstResultUrl(videoId);
        if (resultUrl == null) throw new IllegalStateException("completed KIE task did not include result URLs");
        var response = execute(HTTPMethod.GET, resultUrl, null, "video download");
        if (response.body == null || response.body.length == 0) {
            throw new IllegalStateException("downloaded KIE video is empty");
        }
        return response.body;
    }

    private Map<String, Object> input(VideoGenerationRequest request) {
        var input = new LinkedHashMap<>(defaultInputParams);
        input.put("prompt", request.prompt());
        if (request.inputReferences() != null && !request.inputReferences().isEmpty()) {
            input.put(referenceField(request.model()), referenceUrls(request.inputReferences()));
        }
        var aspectRatio = aspectRatio(request.size());
        if (aspectRatio != null) input.put("aspect_ratio", aspectRatio);
        if (request.seconds() != null) input.put("duration", duration(request));
        return input;
    }

    private String referenceField(String model) {
        return isBytedance(model) ? "reference_image_urls" : "image_urls";
    }

    private Object duration(VideoGenerationRequest request) {
        return isBytedance(request.model()) ? request.seconds() : request.seconds().toString();
    }

    private boolean isBytedance(String model) {
        return model != null && model.startsWith("bytedance/");
    }

    private List<String> referenceUrls(List<MediaReference> references) {
        var urls = new ArrayList<String>();
        for (var reference : references) {
            if (reference.url() != null && !reference.url().isBlank()) {
                urls.add(reference.url());
            } else if (reference.b64Json() != null && !reference.b64Json().isBlank()) {
                urls.add(uploadReferenceImage(reference.b64Json()));
            } else {
                throw new IllegalArgumentException("video reference image requires base64 data or a URL");
            }
        }
        return urls;
    }

    private String uploadReferenceImage(String base64Data) {
        var body = new LinkedHashMap<String, Object>();
        body.put("base64Data", base64Data);
        body.put("uploadPath", "images");
        body.put("fileName", "reference-" + UUID.randomUUID());
        var response = execute(HTTPMethod.POST, uploadUrl, body, "reference image upload");
        var data = taskData(response);
        var url = stringValue(data, "downloadUrl");
        if (url == null || url.isBlank()) throw new IllegalStateException("KIE reference image upload did not return a download URL");
        return url;
    }

    @SuppressWarnings("unchecked")
    private String firstResultUrl(String videoId) {
        var task = taskData(execute(HTTPMethod.GET, recordInfoUrl(videoId), null, "video status"));
        var resultJson = stringValue(task, "resultJson");
        if (resultJson == null || resultJson.isBlank()) return null;
        try {
            var result = (Map<String, Object>) JsonUtil.fromJson(Map.class, resultJson);
            var urls = result.get("resultUrls");
            if (urls instanceof List<?> list && !list.isEmpty()) {
                var url = list.get(0);
                return url instanceof String value && !value.isBlank() ? value : null;
            }
            return null;
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to parse KIE task result JSON", e);
        }
    }

    private String aspectRatio(String size) {
        if (size == null || size.isBlank()) return null;
        var dimensions = size.toLowerCase(Locale.ROOT).split("x");
        if (dimensions.length != 2) return null;
        try {
            var width = Integer.parseInt(dimensions[0].trim());
            var height = Integer.parseInt(dimensions[1].trim());
            if (width == height) return "1:1";
            return width > height ? "16:9" : "9:16";
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeStatus(String state) {
        if (state == null) return "processing";
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "success", "succeeded", "completed", "complete" -> "completed";
            case "fail", "failed", "error", "cancelled", "canceled" -> "failed";
            default -> "processing";
        };
    }

    private String recordInfoUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) throw new IllegalArgumentException("video ID is required");
        return recordInfoUrl + "?taskId=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskData(core.framework.http.HTTPResponse response) {
        var map = (Map<String, Object>) JsonUtil.fromJson(Map.class, response.text());
        var data = map.get("data");
        if (!(data instanceof Map<?, ?> task)) throw new IllegalStateException("KIE task response is missing data");
        return (Map<String, Object>) task;
    }

    @SuppressWarnings("unchecked")
    private void mergeProviderExtra(Map<String, Object> body, Map<String, Object> input, String providerExtra) {
        if (providerExtra == null || providerExtra.isBlank()) return;
        Map<String, Object> extra;
        try {
            extra = (Map<String, Object>) JsonUtil.fromJson(Map.class, providerExtra);
        } catch (RuntimeException e) {
            throw new RuntimeException("invalid providerExtra JSON: " + e.getMessage(), e);
        }
        var extraInput = extra.get("input");
        if (extraInput instanceof Map<?, ?> inputMap) {
            input.putAll((Map<String, Object>) inputMap);
            var remaining = new LinkedHashMap<>(extra);
            remaining.remove("input");
            body.putAll(remaining);
        } else {
            body.putAll(extra);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultInputParams(String requestExtraBody) {
        if (requestExtraBody == null || requestExtraBody.isBlank()) return new LinkedHashMap<>();
        try {
            var extra = (Map<String, Object>) JsonUtil.fromJson(Map.class, requestExtraBody);
            var input = extra.get("input");
            if (input instanceof Map<?, ?> inputMap) return new LinkedHashMap<>((Map<String, Object>) inputMap);
            return new LinkedHashMap<>(extra);
        } catch (RuntimeException e) {
            throw new RuntimeException("invalid request extra body JSON: " + e.getMessage(), e);
        }
    }

    private core.framework.http.HTTPResponse execute(HTTPMethod method, String url, Map<String, Object> body, String operation) {
        var request = new HTTPRequest(method, url);
        if (token != null && !token.isBlank()) {
            request.headers.put("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
            request.body(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        }
        var response = client.execute(request);
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new RuntimeException(operation + " failed: HTTP " + response.statusCode + ": " + response.text());
        }
        return response;
    }

    private String rootUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_MARKET_URL : baseUrl.replaceAll("/+$", "");
    }

    private String stringValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof String string ? string : null;
    }

    private Integer intValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof Number number ? number.intValue() : null;
    }
}
