package ai.core.media;

import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author Stephen
 */
public class KieMediaProvider implements MediaProvider {
    private static final String DEFAULT_UPLOAD_URL = "https://kieai.redpandaai.co";

    // docs.kie.ai model pages: seedance takes 480p/720p/1080p, wan 2.7 takes 720p/1080p
    private static final List<Integer> SEEDANCE_RESOLUTIONS = List.of(480, 720, 1080);
    private static final List<Integer> WAN_RESOLUTIONS = List.of(720, 1080);

    private static final ModelFamily DEFAULT_FAMILY =
            new ModelFamily("", ReferenceMode.ARRAY, "image_urls", DurationType.STRING);

    // longest prefix first; the first match wins
    private static final List<ModelFamily> MODEL_FAMILIES = List.of(
            new ModelFamily("minimax-h3/reference-to-video", ReferenceMode.ARRAY, "reference_image_urls", DurationType.INT),
            new ModelFamily("minimax-h3/image-to-video", ReferenceMode.FIRST_LAST, "first_frame_url", DurationType.INT),
            new ModelFamily("minimax-h3/text-to-video", ReferenceMode.NONE, null, DurationType.INT),
            new ModelFamily("wan/2-7-image-to-video", ReferenceMode.FIRST_LAST, "first_frame_url", DurationType.INT, WAN_RESOLUTIONS),
            new ModelFamily("wan/2-7-r2v", ReferenceMode.ARRAY, "reference_image", DurationType.INT, WAN_RESOLUTIONS),
            new ModelFamily("wan/2-7-", ReferenceMode.NONE, null, DurationType.INT, WAN_RESOLUTIONS),
            new ModelFamily("wan/", ReferenceMode.ARRAY, "image_urls", DurationType.STRING),
            new ModelFamily("bytedance/seedance-2", ReferenceMode.ARRAY, "reference_image_urls", DurationType.INT, SEEDANCE_RESOLUTIONS),
            new ModelFamily("bytedance/seedance-1", ReferenceMode.ARRAY, "input_urls", DurationType.INT, SEEDANCE_RESOLUTIONS),
            new ModelFamily("bytedance/v1-", ReferenceMode.SINGLE, "image_url", DurationType.STRING),
            new ModelFamily("kling-2.6/", ReferenceMode.ARRAY, "image_urls", DurationType.STRING),
            new ModelFamily("kling-3.0/", ReferenceMode.ARRAY, "image_urls", DurationType.STRING),
            new ModelFamily("kling/v3-", ReferenceMode.ARRAY, "image_urls", DurationType.STRING),
            new ModelFamily("kling/v2-", ReferenceMode.SINGLE, "image_url", DurationType.STRING),
            new ModelFamily("grok-imagine/", ReferenceMode.ARRAY, "image_urls", DurationType.STRING),
            new ModelFamily("hailuo/", ReferenceMode.SINGLE, "image_url", DurationType.STRING),
            new ModelFamily("pixverse/", ReferenceMode.ARRAY, "image_urls", DurationType.INT),
            new ModelFamily("happyhorse", ReferenceMode.ARRAY, "image_urls", DurationType.INT)
    );

    // KIE image models take a fixed aspect_ratio enum instead of a pixel size; the widest ratio first
    // only matters for readability, the nearest match is picked by log distance
    private static final List<String> IMAGE_ASPECT_RATIOS = List.of("21:9", "16:9", "3:2", "4:3", "1:1", "3:4", "2:3", "9:16");
    // seedream/nano-banana/flux image editing on KIE all take the references as an image_urls array
    private static final String IMAGE_REFERENCE_FIELD = "image_urls";
    private static final int MAX_IMAGE_REFERENCES = 10;
    private static final String DEFAULT_IMAGE_ASPECT_RATIO = "1:1";
    private static final String DEFAULT_IMAGE_QUALITY = "basic";
    // consecutive transient poll failures tolerated before giving up on a running task
    private static final int MAX_IMAGE_POLL_FAILURES = 3;

    private final Map<String, Object> defaultInputParams;
    private final KieTaskClient client;
    // image tasks are async on KIE but the MediaProvider contract is synchronous, so generateImage polls;
    // package-private so tests do not have to wait on the production cadence
    Duration imagePollInterval = Duration.ofSeconds(2);
    Duration imagePollTimeout = Duration.ofMinutes(5);

    public KieMediaProvider(String baseUrl, String token) {
        this(baseUrl, DEFAULT_UPLOAD_URL, token, null);
    }

    public KieMediaProvider(String baseUrl, String token, String requestExtraBody) {
        this(baseUrl, DEFAULT_UPLOAD_URL, token, requestExtraBody);
    }

    public KieMediaProvider(String baseUrl, String uploadBaseUrl, String token, String requestExtraBody) {
        this.defaultInputParams = defaultInputParams(requestExtraBody);
        this.client = new KieTaskClient(baseUrl, uploadBaseUrl, token);
    }

    /**
     * Per-family KIE contract differences (verified against docs.kie.ai model pages):
     * some families send references as an array field, some as a single image_url,
     * some as first/last frame URLs, and some accept integer durations while others
     * require string enum values. Unknown models default to the image_urls array +
     * string duration mapping.
     */
    private ModelFamily modelFamily(String model) {
        if (model == null) return DEFAULT_FAMILY;
        for (var family : MODEL_FAMILIES) {
            if (model.startsWith(family.prefix())) return family;
        }
        return DEFAULT_FAMILY;
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        if (request.model() == null || request.model().isBlank()) throw new IllegalArgumentException("image model is required");
        if (request.prompt() == null || request.prompt().isBlank()) throw new IllegalArgumentException("image prompt is required");
        if (request.mask() != null) throw new IllegalArgumentException("KIE image generation does not support masks");
        if (request.previousInteractionId() != null && !request.previousInteractionId().isBlank())
            throw new IllegalArgumentException("KIE image generation does not support previous_interaction_id");
        if (request.n() != null && request.n() > 1)
            throw new IllegalArgumentException("KIE image models generate one image per task, got n=" + request.n());

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        var input = imageInput(request);
        body.put("input", input);
        mergeProviderExtra(body, input, request.providerExtra());

        var task = client.createTask(body, request.model(), MediaModelParameterHints.imageHint(request.model()), "image generation");
        var taskId = KieTaskClient.stringValue(task, "taskId");
        if (taskId == null || taskId.isBlank()) throw new IllegalStateException("KIE image task response is missing taskId");
        var images = awaitImages(taskId, request.model());
        return new ImageGenerationResponse(images, new Usage(null, images.size(), null));
    }

    private Map<String, Object> imageInput(ImageGenerationRequest request) {
        var input = new LinkedHashMap<>(defaultInputParams);
        input.put("prompt", request.prompt());
        if (request.inputImages() != null && !request.inputImages().isEmpty()) {
            if (request.model().contains("text-to-image"))
                throw new IllegalArgumentException(request.model() + " does not accept input images, use the image-to-image model instead");
            var urls = referenceUrls(request.inputImages());
            if (urls.size() > MAX_IMAGE_REFERENCES)
                throw new IllegalArgumentException(request.model() + " accepts at most " + MAX_IMAGE_REFERENCES + " input images, got " + urls.size());
            input.put(IMAGE_REFERENCE_FIELD, urls);
        }
        var aspectRatio = imageAspectRatio(request.size());
        if (aspectRatio != null) input.put("aspect_ratio", aspectRatio);
        var quality = imageQuality(request.quality());
        if (quality != null) input.put("quality", quality);
        if (request.outputFormat() != null && !request.outputFormat().isBlank())
            input.put("output_format", request.outputFormat().trim().toLowerCase(Locale.ROOT));
        // KIE marks both as required; fall back only when neither the request nor the provider defaults set them
        input.putIfAbsent("aspect_ratio", DEFAULT_IMAGE_ASPECT_RATIO);
        input.putIfAbsent("quality", DEFAULT_IMAGE_QUALITY);
        return input;
    }

    /**
     * KIE image models accept an aspect_ratio enum, not a pixel size. Callers speak the OpenAI
     * "1024x1536" dialect, so map to the nearest supported ratio; an aspect ratio passed through
     * verbatim is accepted as-is.
     */
    private String imageAspectRatio(String size) {
        var ratio = ratio(size);
        if (ratio == null) return null;
        String nearest = null;
        var nearestDistance = Double.MAX_VALUE;
        for (var candidate : IMAGE_ASPECT_RATIOS) {
            var candidateRatio = ratio(candidate);
            var distance = Math.abs(Math.log(ratio) - Math.log(candidateRatio));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    // accepts both the OpenAI "1024x1536" pixel size and a bare "3:2" aspect ratio
    private Double ratio(String value) {
        if (value == null || value.isBlank()) return null;
        var dimensions = value.trim().toLowerCase(Locale.ROOT).split(value.indexOf(':') >= 0 ? ":" : "x");
        if (dimensions.length != 2) return null;
        try {
            var width = Double.parseDouble(dimensions[0].trim());
            var height = Double.parseDouble(dimensions[1].trim());
            return width <= 0 || height <= 0 ? null : width / height;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * KIE grades images as basic/high (Seedream 5.0 Pro) or basic/high/ultra (Seedream 5.0 Lite).
     * The OpenAI-style low/medium/high/auto values are folded onto that scale; "ultra" is only
     * reachable by asking for it explicitly so a plain "high" never silently upgrades the tier.
     */
    private String imageQuality(String quality) {
        if (quality == null || quality.isBlank()) return null;
        return switch (quality.trim().toLowerCase(Locale.ROOT)) {
            case "basic", "low", "medium" -> "basic";
            case "high" -> "high";
            case "ultra" -> "ultra";
            case "auto" -> null;
            default -> throw new IllegalArgumentException("unsupported image quality: " + quality + ", expected basic/high/ultra");
        };
    }

    private List<ImageData> awaitImages(String taskId, String model) {
        var deadline = System.nanoTime() + imagePollTimeout.toNanos();
        var failures = 0;
        while (true) {
            // always wait first: the task was created milliseconds ago and is never ready yet, and KIE
            // drops the pooled connection after createTask, so an immediate poll reuses a stale one
            sleep(imagePollInterval);
            Map<String, Object> task = null;
            try {
                task = client.recordInfo(taskId, "image status");
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                // the HTTP client runs with retryOnConnectionFailure(false) by design, so transient
                // faults are ours to absorb — dropping a task that is already running (and billed)
                // over one failed poll is far worse than polling again
                if (failures > MAX_IMAGE_POLL_FAILURES) throw e;
            }
            if (task != null) {
                var images = terminalImages(task, taskId, model);
                if (images != null) return images;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("KIE image task did not complete within " + imagePollTimeout + ", taskId=" + taskId);
            }
        }
    }

    /** Images once the task succeeded, null while it is still running; throws when it ended in failure. */
    private List<ImageData> terminalImages(Map<String, Object> task, String taskId, String model) {
        var state = normalizeStatus(KieTaskClient.stringValue(task, "state"));
        if ("failed".equals(state)) {
            var failMsg = KieTaskClient.stringValue(task, "failMsg");
            throw new IllegalStateException("KIE image task failed, taskId=" + taskId
                    + (failMsg == null || failMsg.isBlank() ? "" : ": " + failMsg)
                    + KieTaskClient.parameterHint(model, MediaModelParameterHints.imageHint(model), failMsg));
        }
        if (!"completed".equals(state)) return null;
        var urls = client.resultUrls(task);
        if (urls.isEmpty()) throw new IllegalStateException("completed KIE image task did not include result URLs");
        return downloadImages(urls);
    }

    // KIE result URLs expire; download eagerly so callers that persist artifacts or inline the image
    // (media jobs, GenerateImageTool) keep working after the link goes away
    private List<ImageData> downloadImages(List<String> urls) {
        var images = new ArrayList<ImageData>();
        for (var url : urls) {
            var bytes = client.download(url, "image download");
            if (bytes.length == 0) throw new IllegalStateException("downloaded KIE image is empty, url=" + url);
            images.add(new ImageData(Base64.getEncoder().encodeToString(bytes), url, null));
        }
        return images;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for KIE image task", e);
        }
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        if (request.model() == null || request.model().isBlank()) throw new IllegalArgumentException("video model is required");
        if (request.prompt() == null || request.prompt().isBlank()) throw new IllegalArgumentException("video prompt is required");
        if (request.previousInteractionId() != null && !request.previousInteractionId().isBlank())
            throw new IllegalArgumentException("KIE video generation does not support previous_video_id");

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        var input = input(request);
        body.put("input", input);
        mergeProviderExtra(body, input, request.providerExtra());

        var task = client.createTask(body, request.model(), MediaModelParameterHints.videoHint(request.model()), "video generation");
        var taskId = KieTaskClient.stringValue(task, "taskId");
        if (taskId == null || taskId.isBlank()) throw new IllegalStateException("KIE video task response is missing taskId");
        return new VideoGenerationResponse(taskId, "pending", null, null);
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        var task = client.recordInfo(videoId, "video status");
        var state = KieTaskClient.stringValue(task, "state");
        return new VideoStatusResponse(videoId, normalizeStatus(state), KieTaskClient.intValue(task, "progress"),
                KieTaskClient.stringValue(task, "failMsg"), null, KieTaskClient.doubleValue(task, "creditsConsumed"), null);
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        var status = getVideoStatus(videoId);
        if (!"completed".equals(status.status())) {
            throw new IllegalStateException("KIE video is not completed, state=" + status.status());
        }
        var resultUrl = firstResultUrl(videoId);
        if (resultUrl == null) throw new IllegalStateException("completed KIE task did not include result URLs");
        var bytes = client.download(resultUrl, "video download");
        if (bytes.length == 0) {
            throw new IllegalStateException("downloaded KIE video is empty");
        }
        return bytes;
    }

    private Map<String, Object> input(VideoGenerationRequest request) {
        var input = new LinkedHashMap<>(defaultInputParams);
        input.put("prompt", request.prompt());
        var family = modelFamily(request.model());
        if (request.inputReferences() != null && !request.inputReferences().isEmpty()) {
            applyReferences(input, request, family);
        }
        var aspectRatio = KieOutputSize.aspectRatio(request.size());
        if (aspectRatio != null) input.put("aspect_ratio", aspectRatio);
        var resolution = KieOutputSize.resolution(request.size(), family.resolutions());
        if (resolution != null) input.put("resolution", resolution);
        if (request.seconds() != null) input.put("duration", duration(request, family));
        return input;
    }

    /**
     * The reference arrays are the only token/asset binding the KIE API documents, so the array order
     * here must stay exactly the order the prompt tokens were compiled against — never reorder. Video
     * and audio references ride on their own arrays; putting them into the image array would silently
     * hand the model the wrong kind of asset.
     */
    private void applyReferences(Map<String, Object> input, VideoGenerationRequest request, ModelFamily family) {
        var imageUrls = referenceUrls(request.inputReferences(), MediaModality.IMAGE);
        var videoUrls = referenceUrls(request.inputReferences(), MediaModality.VIDEO);
        var audioUrls = referenceUrls(request.inputReferences(), MediaModality.AUDIO);
        if (!videoUrls.isEmpty()) input.put("reference_video_urls", videoUrls);
        if (!audioUrls.isEmpty()) input.put("reference_audio_urls", audioUrls);
        if (imageUrls.isEmpty()) return;
        switch (family.referenceMode()) {
            case ARRAY -> input.put(family.referenceField(), imageUrls);
            case SINGLE -> {
                if (imageUrls.size() > 1)
                    throw new IllegalArgumentException(request.model() + " accepts exactly one reference image, got " + imageUrls.size());
                input.put(family.referenceField(), imageUrls.getFirst());
            }
            case FIRST_LAST -> {
                if (imageUrls.size() > 2)
                    throw new IllegalArgumentException(request.model() + " accepts at most two reference images (first and last frame), got " + imageUrls.size());
                input.put("first_frame_url", imageUrls.getFirst());
                if (imageUrls.size() == 2) input.put("last_frame_url", imageUrls.get(1));
            }
            case NONE -> throw new IllegalArgumentException(request.model() + " does not accept reference images");
            default -> throw new IllegalArgumentException("unexpected reference mode: " + family.referenceMode());
        }
    }

    private Object duration(VideoGenerationRequest request, ModelFamily family) {
        return family.durationType() == DurationType.INT ? request.seconds() : request.seconds().toString();
    }

    private List<String> referenceUrls(List<MediaReference> references, MediaModality modality) {
        return referenceUrls(references.stream().filter(reference -> reference.modalityOrImage() == modality).toList());
    }

    private List<String> referenceUrls(List<MediaReference> references) {
        var urls = new ArrayList<String>();
        for (var reference : references) {
            if (reference.url() != null && !reference.url().isBlank()) {
                urls.add(reference.url());
            } else if (reference.b64Json() != null && !reference.b64Json().isBlank()) {
                urls.add(client.uploadReferenceImage(reference.b64Json()));
            } else {
                throw new IllegalArgumentException("video reference image requires base64 data or a URL");
            }
        }
        return urls;
    }

    private String firstResultUrl(String videoId) {
        var urls = client.resultUrls(client.recordInfo(videoId, "video status"));
        return urls.isEmpty() ? null : urls.getFirst();
    }

    private String normalizeStatus(String state) {
        if (state == null) return "processing";
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "success", "succeeded", "completed", "complete" -> "completed";
            case "fail", "failed", "error", "cancelled", "canceled" -> "failed";
            default -> "processing";
        };
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

    private enum DurationType { INT, STRING }

    private enum ReferenceMode { ARRAY, SINGLE, FIRST_LAST, NONE }

    /**
     * @param resolutions short-side tiers this family accepts, largest-wins against the requested size.
     *                    Empty means "not verified against the model page": no resolution is sent and the
     *                    model uses its own default, which is what happened for every family before.
     */
    private record ModelFamily(String prefix, ReferenceMode referenceMode, String referenceField, DurationType durationType,
                               List<Integer> resolutions) {
        ModelFamily(String prefix, ReferenceMode referenceMode, String referenceField, DurationType durationType) {
            this(prefix, referenceMode, referenceField, durationType, List.of());
        }
    }
}
