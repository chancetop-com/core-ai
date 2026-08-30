package ai.core.media;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;

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
 * Transport for the KIE unified job API (createTask / recordInfo / base64 upload): HTTP wiring,
 * the {@code {code, msg, data}} envelope and the task result JSON. Model-specific request mapping
 * lives in {@link KieMediaProvider}.
 *
 * @author Stephen
 */
class KieTaskClient {
    private static final String DEFAULT_MARKET_URL = "https://api.kie.ai";
    private static final String CREATE_TASK_PATH = "/api/v1/jobs/createTask";
    private static final String RECORD_INFO_PATH = "/api/v1/jobs/recordInfo";
    private static final String FILE_BASE64_UPLOAD_PATH = "/api/file-base64-upload";
    private static final String DEFAULT_IMAGE_EXTENSION = "png";

    static String stringValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof String string ? string : null;
    }

    static Integer intValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof Number number ? number.intValue() : null;
    }

    static Double doubleValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    // KIE wraps parameter validation failures as business errors (HTTP 200 + code 422, sometimes
    // code 500 with a parameter message); append the model hint so the agent can retry with valid values
    static String parameterHint(String model, String hint, String message) {
        if (hint == null || message == null || !isParameterError(message)) return "";
        return " Allowed parameters for " + model + ": " + hint;
    }

    private static boolean isParameterError(String message) {
        return message.contains("code=422")
                || message.contains("not within the range of allowed options")
                || message.contains("is not supported")
                || message.contains("is required")
                || message.contains("must be");
    }

    private static String rootUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_MARKET_URL : baseUrl.replaceAll("/+$", "");
    }

    /**
     * KIE's upload API documents fileName as "including file extension" — a name without one leaves the
     * stored file untyped, and the models then reject it with "File type not supported". Derive the
     * extension from the data URL's mime type when the caller supplied one.
     */
    private static String referenceFileName(String base64Data) {
        return "reference-" + UUID.randomUUID() + '.' + extension(base64Data);
    }

    private static String extension(String base64Data) {
        if (base64Data == null || !base64Data.startsWith("data:")) return DEFAULT_IMAGE_EXTENSION;
        var separator = base64Data.indexOf(';');
        var slash = base64Data.indexOf('/');
        if (separator < 0 || slash < 0 || slash > separator) return DEFAULT_IMAGE_EXTENSION;
        var subtype = base64Data.substring(slash + 1, separator).toLowerCase(Locale.ROOT);
        if (!subtype.matches("[a-z0-9]{1,8}")) return DEFAULT_IMAGE_EXTENSION;
        return "jpeg".equals(subtype) ? "jpg" : subtype;
    }

    private final String createTaskUrl;
    private final String recordInfoUrl;
    private final String uploadUrl;
    private final String token;
    private final HTTPClient client;

    KieTaskClient(String baseUrl, String uploadBaseUrl, String token) {
        this.createTaskUrl = rootUrl(baseUrl) + CREATE_TASK_PATH;
        this.recordInfoUrl = rootUrl(baseUrl) + RECORD_INFO_PATH;
        this.uploadUrl = rootUrl(uploadBaseUrl) + FILE_BASE64_UPLOAD_PATH;
        this.token = token;
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofMinutes(5))
                .trustAll()
                .build();
    }

    Map<String, Object> createTask(Map<String, Object> body, String model, String hint, String operation) {
        try {
            return taskData(execute(HTTPMethod.POST, createTaskUrl, body, operation));
        } catch (IllegalStateException e) {
            var suffix = parameterHint(model, hint, e.getMessage());
            if (!suffix.isEmpty()) throw new IllegalStateException(e.getMessage() + suffix, e);
            throw e;
        }
    }

    Map<String, Object> recordInfo(String taskId, String operation) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task ID is required");
        var url = recordInfoUrl + "?taskId=" + URLEncoder.encode(taskId, StandardCharsets.UTF_8);
        return taskData(execute(HTTPMethod.GET, url, null, operation));
    }

    byte[] download(String url, String operation) {
        return execute(HTTPMethod.GET, url, null, operation).body;
    }

    String uploadReferenceImage(String base64Data) {
        var body = new LinkedHashMap<String, Object>();
        body.put("base64Data", base64Data);
        body.put("uploadPath", "images");
        body.put("fileName", referenceFileName(base64Data));
        var url = stringValue(taskData(execute(HTTPMethod.POST, uploadUrl, body, "reference image upload")), "downloadUrl");
        if (url == null || url.isBlank()) throw new IllegalStateException("KIE reference image upload did not return a download URL");
        return url;
    }

    @SuppressWarnings("unchecked")
    List<String> resultUrls(Map<String, Object> task) {
        var resultJson = stringValue(task, "resultJson");
        if (resultJson == null || resultJson.isBlank()) return List.of();
        try {
            var result = (Map<String, Object>) JsonUtil.fromJson(Map.class, resultJson);
            if (!(result.get("resultUrls") instanceof List<?> list)) return List.of();
            var urls = new ArrayList<String>();
            for (var url : list) {
                if (url instanceof String value && !value.isBlank()) urls.add(value);
            }
            return urls;
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to parse KIE task result JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskData(HTTPResponse response) {
        var map = (Map<String, Object>) JsonUtil.fromJson(Map.class, response.text());
        if (!successCode(map.get("code"))) {
            throw new IllegalStateException("KIE API error: code=" + map.get("code") + ", msg=" + map.get("msg"));
        }
        var data = map.get("data");
        if (!(data instanceof Map<?, ?> task)) throw new IllegalStateException("KIE task response is missing data, msg=" + map.get("msg"));
        return (Map<String, Object>) task;
    }

    private boolean successCode(Object code) {
        if (code instanceof Number number) return number.intValue() == 200;
        if (code instanceof String string) return "200".equals(string);
        return false;
    }

    private HTTPResponse execute(HTTPMethod method, String url, Map<String, Object> body, String operation) {
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
}
