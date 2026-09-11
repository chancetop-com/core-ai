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
import ai.core.media.reference.MediaReferenceRole;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Stephen
 */
public class VertexGeminiOmniMediaProvider implements MediaProvider {
    private static final String DEFAULT_BASE_URL = "https://aiplatform.googleapis.com/v1beta1";

    private final String interactionsUrl;
    private final GoogleAccessTokenProvider accessTokenProvider;
    private final HTTPClient client;

    public VertexGeminiOmniMediaProvider(String baseUrl, String projectId, String location, GoogleAccessTokenProvider accessTokenProvider) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("Vertex project ID is required");
        if (location == null || location.isBlank()) throw new IllegalArgumentException("Vertex location is required");
        this.interactionsUrl = rootUrl(baseUrl) + "/projects/" + projectId + "/locations/" + location + "/interactions";
        this.accessTokenProvider = accessTokenProvider;
        this.client = new PatchedHTTPClientBuilder().connectTimeout(Duration.ofSeconds(10)).timeout(Duration.ofMinutes(5)).trustAll().build();
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        if (request.model() == null || request.model().isBlank()) throw new IllegalArgumentException("image model is required");
        if (request.prompt() == null || request.prompt().isBlank()) throw new IllegalArgumentException("image prompt is required");
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("input", imageInput(request));
        body.put("response_format", imageResponseFormat(request));
        if (request.previousInteractionId() != null && !request.previousInteractionId().isBlank()) {
            body.put("previous_interaction_id", request.previousInteractionId());
        }
        mergeProviderExtra(body, request.providerExtra());
        var response = responseMap(execute(HTTPMethod.POST, interactionsUrl, body, "image generation"));
        return new ImageGenerationResponse(outputImages(response), usage(response), stringValue(response, "id"));
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        if (request.model() == null || request.model().isBlank()) throw new IllegalArgumentException("video model is required");
        if (request.prompt() == null || request.prompt().isBlank()) throw new IllegalArgumentException("video prompt is required");

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("input", input(request));
        body.put("response_format", List.of(responseFormat(request)));
        body.put("background", Boolean.TRUE);
        if (request.previousInteractionId() != null && !request.previousInteractionId().isBlank()) {
            body.put("previous_interaction_id", request.previousInteractionId());
        }
        body.put("generation_config", Map.of("video_config", Map.of("task", task(request))));
        mergeProviderExtra(body, request.providerExtra());

        var response = execute(HTTPMethod.POST, interactionsUrl, body, "video generation");
        var responseMap = responseMap(response);
        return new VideoGenerationResponse(stringValue(responseMap, "id"), stringValue(responseMap, "status"), null, usage(responseMap));
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        var response = execute(HTTPMethod.GET, interactionUrl(videoId), null, "video status");
        var responseMap = responseMap(response);
        return new VideoStatusResponse(
                stringValue(responseMap, "id"),
                normalizeStatus(stringValue(responseMap, "status")),
                null,
                error(responseMap),
                null,
                null,
                null,
                usage(responseMap));
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var response = execute(HTTPMethod.GET, interactionUrl(videoId), null, "video download");
        var video = outputVideo(responseMap(response));
        var data = stringValue(video, "data");
        if (data == null || data.isBlank()) data = stringValue(video, "data_base64");
        if (data != null && !data.isBlank()) return decodeData(data);
        var uri = stringValue(video, "uri");
        if (uri != null && (uri.startsWith("http://") || uri.startsWith("https://"))) {
            return execute(HTTPMethod.GET, uri, null, "video download").body;
        }
        if (uri != null && uri.startsWith("gs://")) {
            throw new IllegalStateException("video was delivered to Cloud Storage; omit media.vertex.output-gcs-uri to receive local downloadable video data");
        }
        throw new IllegalStateException("completed interaction did not include video data");
    }

    /**
     * Images first, text last — every official sample orders the input that way. Frame anchors lead:
     * "first and last frame interpolation" is two images in the input list (first frame, then last frame)
     * followed by the transition prompt, with no dedicated field; other references follow the frames and are
     * told apart by the prompt.
     */
    private List<Map<String, Object>> input(VideoGenerationRequest request) {
        var input = new ArrayList<Map<String, Object>>();
        for (var reference : orderedReferences(request)) input.add(referenceInput(reference));
        input.add(Map.of("type", "text", "text", request.prompt()));
        return input;
    }

    private List<MediaReference> orderedReferences(VideoGenerationRequest request) {
        if (request.inputReferences() == null) return List.of();
        var ordered = new ArrayList<MediaReference>(request.inputReferences().size());
        request.inputReferences().stream().filter(reference -> reference.role() == MediaReferenceRole.FIRST_FRAME).findFirst().ifPresent(ordered::add);
        request.inputReferences().stream().filter(reference -> reference.role() == MediaReferenceRole.LAST_FRAME).findFirst().ifPresent(ordered::add);
        for (var reference : request.inputReferences()) {
            if (reference.role() == null || !reference.role().isFrame()) ordered.add(reference);
        }
        return ordered;
    }

    private boolean hasFrameAnchor(VideoGenerationRequest request) {
        return request.inputReferences() != null && request.inputReferences().stream().anyMatch(reference -> reference.role() != null && reference.role().isFrame());
    }

    private List<Map<String, Object>> imageInput(ImageGenerationRequest request) {
        var input = new ArrayList<Map<String, Object>>();
        input.add(Map.of("type", "text", "text", request.prompt()));
        if (request.inputImages() != null) {
            for (var image : request.inputImages()) input.add(referenceInput(image));
        }
        return input;
    }

    private Map<String, Object> imageResponseFormat(ImageGenerationRequest request) {
        var format = new LinkedHashMap<String, Object>();
        format.put("type", "image");
        format.put("mime_type", "jpeg".equalsIgnoreCase(request.outputFormat()) ? "image/jpeg" : "image/png");
        var aspectRatio = aspectRatio(request.size());
        if (aspectRatio != null) format.put("aspect_ratio", aspectRatio);
        return format;
    }

    private Map<String, Object> responseFormat(VideoGenerationRequest request) {
        var format = new LinkedHashMap<String, Object>();
        format.put("type", "video");
        if (request.seconds() != null) format.put("duration", request.seconds() + "s");
        var aspectRatio = aspectRatio(request.size());
        if (aspectRatio != null) format.put("aspect_ratio", aspectRatio);
        var resolution = resolution(request.size());
        if (resolution != null) format.put("resolution", resolution);
        return format;
    }

    // 360p / 720p (default) / 1080p / 4k by the short side of the requested size; nothing sent without a size
    private String resolution(String size) {
        if (size == null || size.isBlank()) return null;
        var parts = size.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return null;
        try {
            var shortSide = Math.min(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            if (shortSide >= 2160) return "4k";
            if (shortSide >= 1080) return "1080p";
            if (shortSide >= 720) return "720p";
            return "360p";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> referenceInput(MediaReference reference) {
        var image = new LinkedHashMap<String, Object>();
        image.put("type", "image");
        image.put("mime_type", mimeType(reference.b64Json()));
        if (reference.b64Json() != null && !reference.b64Json().isBlank()) {
            image.put("data", base64Data(reference.b64Json()));
            return image;
        }
        if (reference.url() != null && !reference.url().isBlank()) {
            image.put("uri", reference.url());
            return image;
        }
        throw new IllegalArgumentException("video reference image requires base64 data or a URI");
    }

    private String mimeType(String value) {
        if (value == null || !value.startsWith("data:")) return "image/png";
        var separator = value.indexOf(',');
        if (separator < 0) throw new IllegalArgumentException("invalid image data URL");
        var metadata = value.substring(5, separator);
        var semicolon = metadata.indexOf(';');
        return semicolon < 0 ? metadata : metadata.substring(0, semicolon);
    }

    private String base64Data(String value) {
        if (value == null || !value.startsWith("data:")) return value;
        var separator = value.indexOf(',');
        if (separator < 0) throw new IllegalArgumentException("invalid image data URL");
        return value.substring(separator + 1);
    }

    // a frame anchor makes it image_to_video (interpolation when both frames are present); references alone are reference_to_video
    private String task(VideoGenerationRequest request) {
        if (hasFrameAnchor(request)) return "image_to_video";
        return request.inputReferences() == null || request.inputReferences().isEmpty() ? "text_to_video" : "reference_to_video";
    }

    @SuppressWarnings("unchecked")
    private List<ImageData> outputImages(Map<String, Object> interaction) {
        var images = new ArrayList<ImageData>();
        appendOutputImages(images, interaction.get("output"));
        if (!images.isEmpty()) return images;
        var steps = (List<Map<String, Object>>) interaction.get("steps");
        if (steps != null) {
            for (var step : steps) appendOutputImages(images, step.get("content"));
        }
        return images;
    }

    @SuppressWarnings("unchecked")
    private void appendOutputImages(List<ImageData> images, Object content) {
        if (!(content instanceof List<?> items)) return;
        for (var item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            var image = (Map<String, Object>) map;
            if (!"image".equals(image.get("type"))) continue;
            var data = stringValue(image, "data");
            if (data == null) data = stringValue(image, "data_base64");
            if (data != null) images.add(new ImageData(base64Data(data), stringValue(image, "uri"), null));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputVideo(Map<String, Object> interaction) {
        var directOutput = videoFromContent(interaction.get("output"));
        if (directOutput != null) return directOutput;
        var steps = (List<Map<String, Object>>) interaction.get("steps");
        if (steps == null) throw new IllegalStateException("completed interaction did not include output steps");
        for (var step : steps) {
            var video = videoFromContent(step.get("content"));
            if (video != null) return video;
        }
        throw new IllegalStateException("completed interaction did not include video output");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> videoFromContent(Object content) {
        if (content instanceof Map<?, ?> map) {
            var typedMap = (Map<String, Object>) map;
            if ("video".equals(typedMap.get("type"))) return typedMap;
            return videoFromContent(typedMap.get("content"));
        }
        if (!(content instanceof List<?> items)) return null;
        for (var item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            var typedMap = (Map<String, Object>) map;
            if ("video".equals(typedMap.get("type"))) return typedMap;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseMap(core.framework.http.HTTPResponse response) {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, response.text());
    }

    @SuppressWarnings("unchecked")
    private void mergeProviderExtra(Map<String, Object> body, String providerExtra) {
        if (providerExtra == null || providerExtra.isBlank()) return;
        body.putAll((Map<String, Object>) JsonUtil.fromJson(Map.class, providerExtra));
    }

    @SuppressWarnings("unchecked")
    private String error(Map<String, Object> response) {
        var error = response.get("error");
        if (error instanceof Map<?, ?> errorMap) return stringValue((Map<String, Object>) errorMap, "message");
        if (error instanceof String value) return value;
        // failed interactions report an errors[] array, e.g. [{code: "content_blocked", message: "..."}]
        if (response.get("errors") instanceof List<?> errors && !errors.isEmpty() && errors.getFirst() instanceof Map<?, ?> first) {
            var firstError = (Map<String, Object>) first;
            var message = stringValue(firstError, "message");
            var code = stringValue(firstError, "code");
            if (message == null) return code;
            return code == null ? message : code + ": " + message;
        }
        return null;
    }

    private core.framework.http.HTTPResponse execute(HTTPMethod method, String url, Map<String, Object> body, String operation) {
        var request = new HTTPRequest(method, url);
        request.headers.put("Authorization", "Bearer " + accessTokenProvider.accessToken());
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

    private byte[] decodeData(String data) {
        var marker = data.indexOf(',');
        var encoded = marker >= 0 && data.startsWith("data:") ? data.substring(marker + 1) : data;
        return Base64.getDecoder().decode(encoded);
    }

    private String interactionUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) throw new IllegalArgumentException("video ID is required");
        return interactionsUrl + "/" + videoId;
    }

    private String rootUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
    }

    private String aspectRatio(String size) {
        if (size == null || size.isBlank()) return null;
        var dimensions = size.toLowerCase(Locale.ROOT).split("x");
        if (dimensions.length != 2) return null;
        try {
            var width = Integer.parseInt(dimensions[0]);
            var height = Integer.parseInt(dimensions[1]);
            return width >= height ? "16:9" : "9:16";
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeStatus(String status) {
        if (status == null) return "processing";
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "complete", "completed", "succeeded", "success" -> "completed";
            case "failed", "cancelled", "canceled", "error" -> "failed";
            default -> "processing";
        };
    }

    private String stringValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof String string ? string : null;
    }

    /**
     * Interactions usage: thinking tokens are billed as output tokens, tool-use tokens are input-side, and only the
     * video output tokens are priced apart from the text ones (output_cost_per_video_token).
     */
    @SuppressWarnings("unchecked")
    private Usage usage(Map<String, Object> interaction) {
        if (!(interaction.get("usage") instanceof Map<?, ?> map)) return null;
        var usage = (Map<String, Object>) map;
        var totalTokens = intValue(usage, "total_tokens");
        var inputTokens = sumOrNull(intValue(usage, "total_input_tokens"), intValue(usage, "total_tool_use_tokens"));
        var outputTokens = sumOrNull(intValue(usage, "total_output_tokens"), intValue(usage, "total_thought_tokens"));
        var inputTextTokens = modalityTokens(usage, "input_tokens_by_modality", "text");
        var inputImageTokens = modalityTokens(usage, "input_tokens_by_modality", "image");
        var outputVideoTokens = modalityTokens(usage, "output_tokens_by_modality", "video");
        if (totalTokens == null && inputTokens == null && outputTokens == null && outputVideoTokens == null) return null;
        return new Usage(totalTokens, null, null, inputTokens, outputTokens,
                inputTextTokens, inputImageTokens, null, outputVideoTokens);
    }

    @SuppressWarnings("unchecked")
    private Integer modalityTokens(Map<String, Object> usage, String field, String modality) {
        if (!(usage.get(field) instanceof List<?> entries)) return null;
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            var tokens = (Map<String, Object>) map;
            if (modality.equals(tokens.get("modality"))) return intValue(tokens, "tokens");
        }
        return null;
    }

    private Integer sumOrNull(Integer first, Integer second) {
        if (first == null && second == null) return null;
        return (first == null ? 0 : first) + (second == null ? 0 : second);
    }

    private Integer intValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof Number number ? number.intValue() : null;
    }
}
