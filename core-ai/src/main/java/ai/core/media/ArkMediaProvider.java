package ai.core.media;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Volcano Engine Ark (火山方舟) video generation — the native asynchronous task API that hosts the
 * Doubao Seedance family, not the OpenAI-compatible surface of the same host.
 * <p>
 * A task carries one {@code content} array whose items are typed ({@code text} / {@code image_url} /
 * {@code video_url} / {@code audio_url}) and role-tagged ({@code reference_image}, {@code first_frame},
 * {@code last_frame}, {@code reference_video}, {@code reference_audio}). The prompt addresses those
 * assets positionally inside their own type ({@code @Image1}, {@code @Video1}, {@code @Audio1}), which is
 * the same binding {@link ai.core.media.reference.MediaReferenceCompiler} compiles the array against —
 * never reorder one without the other.
 * <p>
 * Frame anchors and references are mutually exclusive scenes upstream ({@code first_frame}/{@code last_frame}
 * versus the multimodal-reference route): a family whose profile carries {@code frameViaReference} keeps a
 * frame as the first reference image instead, which its own docs describe as the way to name an opening
 * frame from inside the reference set.
 *
 * @author stephen
 */
public class ArkMediaProvider implements MediaProvider {
    private static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String TASKS_PATH = "/contents/generations/tasks";

    // Doubao Seedance documents 480p/720p/1080p across the family (2.0 fast and mini stop at 720p); a tier
    // is only sent when the caller gave a size, so a family that rejects one is a config problem, not a
    // silent downgrade — the model list in the prompt carries the per-family vocabulary.
    private static final List<Integer> RESOLUTIONS = List.of(480, 720, 1080);
    // the documented `ratio` enum; the requested size is matched to the nearest of these
    private static final List<String> ASPECT_RATIOS = List.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");

    // the drama render path hands audio and video references through provider_extra rather than as
    // MediaReference items (they have no positional slot in the compiled prompt), so the same internal
    // dialect KIE uses is translated into Ark content items here
    private static final String VIDEO_REFERENCE_FIELD = "reference_video_urls";
    private static final String AUDIO_REFERENCE_FIELD = "reference_audio_urls";

    private static final String IMAGE_TYPE = "image_url";
    private static final String VIDEO_TYPE = "video_url";
    private static final String AUDIO_TYPE = "audio_url";
    private static final String REFERENCE_IMAGE_ROLE = "reference_image";
    private static final String REFERENCE_VIDEO_ROLE = "reference_video";
    private static final String REFERENCE_AUDIO_ROLE = "reference_audio";

    private final Map<String, Object> defaultBodyParams;
    private final String createTaskUrl;
    private final String taskUrl;
    private final String apiKey;
    private final HTTPClient client;

    public ArkMediaProvider(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    public ArkMediaProvider(String baseUrl, String apiKey, String requestExtraBody) {
        this.defaultBodyParams = defaultBodyParams(requestExtraBody);
        this.createTaskUrl = rootUrl(baseUrl) + TASKS_PATH;
        this.taskUrl = this.createTaskUrl + "/";
        this.apiKey = apiKey;
        this.client = new PatchedHTTPClientBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofMinutes(5))
                .trustAll()
                .build();
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        throw new UnsupportedOperationException("Volcano Ark media provider generates video only; use an image provider for image generation");
    }

    @Override
    public boolean acceptsImageReferences() {
        return false;
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        if (request.model() == null || request.model().isBlank()) throw new IllegalArgumentException("video model is required");
        if (request.prompt() == null || request.prompt().isBlank()) throw new IllegalArgumentException("video prompt is required");
        if (request.previousInteractionId() != null && !request.previousInteractionId().isBlank())
            throw new IllegalArgumentException("Volcano Ark video generation does not support previous_video_id; send the source clip as a reference video to edit it");

        var body = new LinkedHashMap<String, Object>(defaultBodyParams);
        var content = content(request);
        body.put("model", request.model());
        body.put("content", content);
        var profile = VideoModelProfiles.lookup(request.model());
        if (request.seconds() != null) body.put("duration", profile.durations().snap(request.seconds()));
        var size = size(request, profile);
        if (size.aspectRatio() != null) body.put("ratio", size.aspectRatio());
        if (size.resolution() != null) body.put("resolution", size.resolution());
        // native synchronized audio is the reason these families are picked for dialogue
        if (profile.audioParam() != null) body.putIfAbsent(profile.audioParam(), Boolean.TRUE);
        mergeProviderExtra(body, content, request.providerExtra());

        var response = map(execute(HTTPMethod.POST, createTaskUrl, body, "video generation"));
        var taskId = stringValue(response, "id");
        if (taskId == null || taskId.isBlank()) taskId = stringValue(response, "task_id");
        if (taskId == null || taskId.isBlank()) throw new IllegalStateException("Volcano Ark video task response is missing id");
        return new VideoGenerationResponse(taskId, "pending", null, null);
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        var task = task(videoId);
        return new VideoStatusResponse(videoId, normalizeStatus(stringValue(task, "status")), progress(task),
                error(task), null, null, null, usage(task));
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var task = task(videoId);
        if (!"completed".equals(normalizeStatus(stringValue(task, "status")))) {
            throw new IllegalStateException("Volcano Ark video is not completed, taskId=" + videoId);
        }
        var url = videoUrl(task);
        if (url == null || url.isBlank()) throw new IllegalStateException("completed Volcano Ark task did not include a video URL");
        var bytes = execute(HTTPMethod.GET, url, null, "video download").body;
        if (bytes.length == 0) throw new IllegalStateException("downloaded Volcano Ark video is empty");
        return bytes;
    }

    /**
     * Text first, then the references in the order the prompt tokens were compiled against. Ark numbers a
     * reference inside its own modality ({@code @Image1} is the first image item whatever its role), so the
     * array order is the only binding the prompt has.
     */
    private List<Map<String, Object>> content(VideoGenerationRequest request) {
        var references = request.inputReferences() == null ? List.<MediaReference>of() : request.inputReferences();
        var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", request.prompt()));

        var images = references.stream().filter(reference -> reference.modalityOrImage() == MediaModality.IMAGE).toList();
        var first = images.stream().filter(reference -> reference.role() == MediaReferenceRole.FIRST_FRAME).findFirst().orElse(null);
        var last = images.stream().filter(reference -> reference.role() == MediaReferenceRole.LAST_FRAME).findFirst().orElse(null);
        var others = images.stream().filter(reference -> reference.role() == null || !reference.role().isFrame()).toList();
        var profile = VideoModelProfiles.lookup(request.model());
        var framed = profile.hasFrameSlots() && (first != null || last != null);
        // a frame-exclusive family refuses a frame next to reference images — except the families whose docs give
        // the multimodal-reference way out, where the frame rides as a reference the prompt names as the opening
        if (framed && !others.isEmpty() && !profile.frameViaReference())
            throw new IllegalArgumentException(request.model() + " takes either frame images or reference images, not both: drop the "
                    + others.size() + " reference image(s) or the frame");
        var frameRoles = framed && others.isEmpty();
        if (frameRoles) {
            // the compiled order puts the frame anchors first, and both keep that order here
            if (first != null) content.add(referenceItem(IMAGE_TYPE, first, "first_frame"));
            if (last != null) content.add(referenceItem(IMAGE_TYPE, last, "last_frame"));
        } else {
            for (var image : images) content.add(referenceItem(IMAGE_TYPE, image, REFERENCE_IMAGE_ROLE));
        }
        for (var reference : references) {
            if (reference.modalityOrImage() == MediaModality.VIDEO) content.add(referenceItem(VIDEO_TYPE, reference, REFERENCE_VIDEO_ROLE));
            if (reference.modalityOrImage() == MediaModality.AUDIO) content.add(referenceItem(AUDIO_TYPE, reference, REFERENCE_AUDIO_ROLE));
        }
        return content;
    }

    // the item key repeats the type ("image_url": {"url": ...}), which is what the API documents
    private Map<String, Object> referenceItem(String type, MediaReference reference, String role) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", type);
        item.put(type, Map.of("url", referenceUrl(reference)));
        item.put("role", role);
        return item;
    }

    // Ark takes a public URL, a data URL ("data:image/png;base64,...", lowercase subtype) or an asset:// id
    private String referenceUrl(MediaReference reference) {
        if (reference.url() != null && !reference.url().isBlank()) return reference.url();
        if (reference.b64Json() != null && !reference.b64Json().isBlank()) {
            return reference.b64Json().startsWith("data:")
                    ? reference.b64Json()
                    : "data:image/png;base64," + reference.b64Json();
        }
        throw new IllegalArgumentException("Ark video reference requires base64 data or a URL");
    }

    /**
     * A frame anchor locks the output ratio to the frame (Ark documents {@code adaptive} for every frame
     * scene), so the requested size only decides the resolution tier there.
     */
    private OutputSize size(VideoGenerationRequest request, VideoModelProfiles.Profile profile) {
        var aspectRatio = MediaOutputSize.nearestAspectRatio(request.size(), ASPECT_RATIOS);
        if (profile.hasFrameSlots() && request.inputReferences() != null
                && request.inputReferences().stream().anyMatch(reference -> reference.role() != null && reference.role().isFrame())) {
            aspectRatio = "adaptive";
        }
        return new OutputSize(aspectRatio, MediaOutputSize.resolution(request.size(), RESOLUTIONS));
    }

    private Map<String, Object> task(String videoId) {
        if (videoId == null || videoId.isBlank()) throw new IllegalArgumentException("video ID is required");
        return map(execute(HTTPMethod.GET, taskUrl + videoId, null, "video status"));
    }

    private String videoUrl(Map<String, Object> task) {
        var content = task.get("content");
        if (content instanceof Map<?, ?> map) {
            var url = map.get("video_url");
            if (url instanceof String value) return value;
        }
        return null;
    }

    private Integer progress(Map<String, Object> task) {
        var progress = task.get("progress");
        if (progress instanceof Number number) return number.intValue();
        if (progress instanceof String value) {
            var digits = value.replace("%", "").trim();
            try {
                return digits.isEmpty() ? null : (int) Double.parseDouble(digits);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String error(Map<String, Object> task) {
        if (!(task.get("error") instanceof Map<?, ?> map)) return null;
        var error = (Map<String, Object>) map;
        var message = stringValue(error, "message");
        var code = stringValue(error, "code");
        if (message == null) return code;
        return code == null ? message : code + ": " + message;
    }

    /**
     * Ark bills token-wise and reports the produced video tokens as {@code usage.completion_tokens}; they are
     * also the video-token count a token-priced catalog entry would be settled against.
     */
    @SuppressWarnings("unchecked")
    private Usage usage(Map<String, Object> task) {
        if (!(task.get("usage") instanceof Map<?, ?> map)) return null;
        var usage = (Map<String, Object>) map;
        var totalTokens = intValue(usage, "total_tokens");
        var outputTokens = intValue(usage, "completion_tokens");
        var inputTokens = intValue(usage, "prompt_tokens");
        if (totalTokens == null && outputTokens == null && inputTokens == null) return null;
        return new Usage(totalTokens, null, null, inputTokens, outputTokens, null, null, null, outputTokens);
    }

    /**
     * Ark has no {@code input} object: a request body carries the generation parameters at its top level. Both
     * the flat dialect and the {@code {"input":{...}}} form are accepted so a caller written against the KIE
     * families keeps working; the two reference-array keys are translated into content items, everything else
     * rides on the body.
     */
    @SuppressWarnings("unchecked")
    private void mergeProviderExtra(Map<String, Object> body, List<Map<String, Object>> content, String providerExtra) {
        if (providerExtra == null || providerExtra.isBlank()) return;
        Map<String, Object> extra;
        try {
            extra = (Map<String, Object>) JsonUtil.fromJson(Map.class, providerExtra);
        } catch (RuntimeException e) {
            throw new RuntimeException("invalid providerExtra JSON: " + e.getMessage(), e);
        }
        var flattened = new LinkedHashMap<String, Object>();
        if (extra.get("input") instanceof Map<?, ?> input) flattened.putAll((Map<String, Object>) input);
        flattened.putAll(extra);
        flattened.remove("input");
        // replacing either would silently drop the prompt or the compiled reference order
        for (var reserved : List.of("model", "content")) {
            if (flattened.containsKey(reserved))
                throw new IllegalArgumentException("provider_extra must not set \"" + reserved + "\"; use input_references, reference_video_urls"
                        + " or reference_audio_urls for assets the model should look at");
        }
        appendReferences(content, flattened.remove(VIDEO_REFERENCE_FIELD), VIDEO_TYPE, REFERENCE_VIDEO_ROLE);
        appendReferences(content, flattened.remove(AUDIO_REFERENCE_FIELD), AUDIO_TYPE, REFERENCE_AUDIO_ROLE);
        body.putAll(flattened);
    }

    @SuppressWarnings("unchecked")
    private void appendReferences(List<Map<String, Object>> content, Object value, String type, String role) {
        if (value instanceof String single) {
            content.add(referenceItem(type, new MediaReference(single, null), role));
            return;
        }
        if (!(value instanceof List<?> list)) return;
        for (var url : list) {
            if (url instanceof String single && !single.isBlank()) content.add(referenceItem(type, new MediaReference(single, null), role));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultBodyParams(String requestExtraBody) {
        if (requestExtraBody == null || requestExtraBody.isBlank()) return Map.of();
        try {
            var extra = (Map<String, Object>) JsonUtil.fromJson(Map.class, requestExtraBody);
            var defaults = new LinkedHashMap<String, Object>();
            if (extra.get("input") instanceof Map<?, ?> input) defaults.putAll((Map<String, Object>) input);
            defaults.putAll(extra);
            defaults.remove("input");
            return defaults;
        } catch (RuntimeException e) {
            throw new RuntimeException("invalid request extra body JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HTTPResponse response) {
        if (response.body == null || response.body.length == 0) throw new IllegalStateException("Volcano Ark returned an empty response");
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, response.text());
    }

    private HTTPResponse execute(HTTPMethod method, String url, Map<String, Object> body, String operation) {
        var request = new HTTPRequest(method, url);
        if (apiKey != null && !apiKey.isBlank()) request.headers.put("Authorization", "Bearer " + apiKey);
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

    private String normalizeStatus(String status) {
        if (status == null) return "processing";
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "succeeded", "success", "completed", "complete" -> "completed";
            case "failed", "expired", "cancelled", "canceled" -> "failed";
            default -> "processing";
        };
    }

    private String stringValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof String string ? string : null;
    }

    private Integer intValue(Map<String, Object> map, String name) {
        var value = map.get(name);
        return value instanceof Number number ? number.intValue() : null;
    }

    private String rootUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
    }

    /**
     * @param aspectRatio the {@code ratio} enum, {@code adaptive} for a frame scene, null when the caller
     *                    gave no size
     * @param resolution  the {@code resolution} tier the requested size covers, null without a size
     */
    private record OutputSize(String aspectRatio, String resolution) {
    }
}
