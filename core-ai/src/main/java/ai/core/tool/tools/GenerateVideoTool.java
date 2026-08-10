package ai.core.tool.tools;

import com.fasterxml.jackson.core.type.TypeReference;

import ai.core.agent.ExecutionContext;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.media.MediaProvider;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.utils.JsonUtil;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.util.Strings;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Submits an asynchronous video generation request. Returns a pending task ID.
 * The agent should poll via {@code async_task_output} (which calls this tool's
 * built-in {@code poll()} method) to check completion. The manual
 * {@code get_video_status} tool is also available for explicit status checks.
 * <p>
 * Videos typically take 1–10 minutes to generate.
 * <p>
 * Only ONE video task may be in flight per session: a new {@code generate_video}
 * submission is rejected while a previous task has not reached a terminal status
 * (completed/failed) via {@code get_video_status}.
 *
 * @author stephen
 */
public final class GenerateVideoTool extends ToolCall {
    public static final String TOOL_NAME = "generate_video";
    static final String PENDING_VIDEO_TASK_CONTEXT_KEY = "__pending_video_task_id";
    static final String VIDEO_SUBMIT_FAIL_COUNT_CONTEXT_KEY = "__video_submit_fail_count";
    private static final int MAX_REFERENCE_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private static final String TOOL_DESC = buildToolDescription();

    // built at runtime so the description is not inlined into referencing class files (duplicated constant pools)
    private static String buildToolDescription() {
        return """
                Generate a video from a text prompt. This is an asynchronous operation — the tool
                returns a task ID immediately, and the agent must poll get_video_status with that
                ID to check when the video is ready.

                IMPORTANT — ONE VIDEO TASK AT A TIME:
                This session can only have ONE video generation task in progress. After this tool
                returns a video_id, you MUST call get_video_status with that ID and keep polling
                until it reports "completed" or "failed" before submitting another generate_video
                request. Submitting a new request while a previous task is still processing will
                be REJECTED. Do not start a new task just because the previous one is taking a
                while — video generation takes 1-10 minutes.

                Parameters:
                - prompt (required): A detailed text description of the video scene
                - model: Optional. The video model to use (model_id from the Configured video models
                  list below). Omit to use the default model (the session default if set, otherwise
                  the system default). Do NOT guess model names.
                - model_scope: Optional, "once" (default) or "session". "session" makes the model
                  the default for the rest of the conversation; pass model="" with
                  model_scope="session" to clear the session default and fall back to the system default.
                - seconds: Optional, defaults to 10. The provider maximum is 10 seconds; larger
                  values are clamped to 10.
                - size: Optional, e.g. "1280x720" or "720x1280". The provider renders video at
                  most 720p; aspect ratio is derived from the width/height.
                - input_references: Optional JSON array of reference images. Each item must be
                  an object with exactly one of:
                    - {"b64Json": "data:image/jpeg;base64,..."} — a full data URL. The
                      "data:<mime>;base64," prefix is REQUIRED; raw base64 without the prefix is
                      REJECTED.
                    - {"url": "https://..."} — an http(s) image URL; the server downloads the
                      image automatically.
                  If omitted, the images attached to the current chat message are used
                  automatically.
                  NEVER embed huge base64 blobs in the tool arguments — prefer passing an image
                  url or relying on the attached images; the server converts them.
                - previous_video_id: A video_id from this gateway to edit conversationally. Supported by Gemini Omni.
                - provider_extra: JSON string with model-specific input parameters, merged into the
                  upstream request. Format: {"input": {...}} puts the keys into the model input, any
                  other keys go to the request top level. Image/video URLs must be public http(s)
                  URLs the upstream can fetch — for local images use input_references instead.
                """.stripIndent();
    }

    /**
     * Builds the tool description with the currently configured gateway video models appended,
     * so the agent knows which models exist and which model-specific parameters each accepts.
     */
    public static String buildDescription(List<MediaModelHint> videoModels) {
        var description = new StringBuilder(TOOL_DESC);
        if (videoModels != null && !videoModels.isEmpty()) {
            description.append("\n\nConfigured video models (pass their model_id in the model parameter; "
                    + "use model_scope=\"session\" to make it the default for the rest of the conversation):");
            for (var model : videoModels) {
                description.append("\n- ").append(model.modelId());
                if (model.providerName() != null) description.append(" (").append(model.providerName()).append(')');
                var hint = MediaModelParameterHints.videoHint(model.upstreamModel());
                if (hint != null) description.append(": ").append(hint);
            }
        }
        return description.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(MediaProvider mediaProvider) {
        return new Builder().mediaProvider(mediaProvider);
    }

    private static Integer parseInteger(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Applies the model_scope argument to the session media model variable, shared by the
     * generate_video / generate_image tools. Called only after a successful generation so a
     * failed call never changes the session default; an empty model clears the session default.
     */
    static void applySessionModelScope(ExecutionContext context, Map<String, Object> args, String variableKey) {
        if (!"session".equals(getStringValue(args, "model_scope"))) return;
        var model = getStringValue(args, "model");
        if (model == null || model.isBlank()) {
            context.getCustomVariables().remove(variableKey);
        } else {
            context.getCustomVariables().put(variableKey, model);
        }
    }

    private final MediaProvider mediaProvider;
    private final ReferenceImageLoader referenceImageLoader;

    private GenerateVideoTool(MediaProvider mediaProvider, ReferenceImageLoader referenceImageLoader) {
        this.mediaProvider = mediaProvider;
        this.referenceImageLoader = referenceImageLoader;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("generate_video requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        var provider = context.getVideoMediaProvider();
        if (provider == null) return ToolCallResult.failed("no media provider configured");
        try {
            var args = parseArguments(arguments);
            var prompt = getStringValue(args, "prompt");
            if (Strings.isBlank(prompt)) return ToolCallResult.failed("prompt is required");

            var pendingTaskId = pendingVideoTask(context);
            if (pendingTaskId != null) {
                return ToolCallResult.failed(
                        "There is already a video generation task in progress for this session: video_id=" + pendingTaskId + ". "
                                + "You MUST call get_video_status with that video_id and wait until it reports 'completed' or 'failed' "
                                + "before submitting a new generate_video request.")
                        .withDuration(System.currentTimeMillis() - startTime)
                        .withStats("video_id", pendingTaskId);
            }

            var request = new VideoGenerationRequest(
                    getStringValue(args, "model") != null ? getStringValue(args, "model") : defaultModel(context),
                    prompt,
                    parseInteger(args, "seconds"),
                    getStringValue(args, "size"),
                    inputReferences(args, context),
                    getStringValue(args, "provider_extra"),
                    getStringValue(args, "previous_video_id"));

            var response = provider.generateVideo(request);
            var videoId = response.id();
            applySessionModelScope(context, args, "media.video.model");
            context.getCustomVariables().put(PENDING_VIDEO_TASK_CONTEXT_KEY, videoId);
            resetFailCount(context);

            var result = "Video generation submitted.\n"
                    + "video_id: " + videoId + "\n"
                    + "Videos typically take 1–10 minutes.\n"
                    + "Poll get_video_status with this video_id until it reports 'completed' or 'failed' — do not submit another video task before then.";

            return ToolCallResult.pending(videoId, result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("video_id", videoId);
        } catch (Exception e) {
            return ToolCallResult.failed(failureMessage(e, context), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String failureMessage(Exception e, ExecutionContext context) {
        var count = incrementFailCount(context);
        var message = "Video generation failed: " + sanitize(e.getMessage() != null ? e.getMessage() : e.toString());
        if (count >= 2) {
            message += "\n\nYou have now failed " + count + " times in a row. Do NOT keep guessing. "
                    + "Re-read the generate_video tool description: input_references items must be either "
                    + "a full data URL (\"data:image/jpeg;base64,...\") or an http(s) url (the server downloads it), "
                    + "and the model must be one actually configured in the gateway.";
        }
        return message;
    }

    private String sanitize(String message) {
        var sanitized = message
                .replaceAll("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{100,}", "data:image/...;base64,[truncated]")
                .replaceAll("[A-Za-z0-9+/=]{200,}", "[base64 truncated]");
        if (sanitized.length() > MAX_ERROR_MESSAGE_LENGTH) {
            sanitized = sanitized.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "... (message truncated)";
        }
        return sanitized;
    }

    private List<MediaReference> inputReferences(Map<String, Object> args, ExecutionContext context) {
        var references = getStringValue(args, "input_references");
        if (!Strings.isBlank(references)) {
            try {
                List<MediaReference> parsed = JsonUtil.fromJson(new TypeReference<>() { }, references);
                return parsed.stream().map(this::resolveReference).toList();
            } catch (Exception e) {
                throw new IllegalArgumentException("input_references must be a JSON array of reference images", e);
            }
        }
        var attachedContents = context.getAttachedContents();
        if (attachedContents == null) return null;
        return attachedContents.stream()
                .filter(content -> content.type == ExecutionContext.AttachedContent.AttachedContentType.IMAGE)
                .map(this::mediaReference)
                .toList();
    }

    // server-side conversion so the LLM never has to embed base64: any http(s) url is downloaded and turned into a data URL
    private MediaReference resolveReference(MediaReference reference) {
        if (reference.b64Json() != null && !reference.b64Json().isBlank()) return reference;
        if (reference.url() != null && !reference.url().isBlank()) {
            var loaded = referenceImageLoader.load(reference.url());
            var mimeType = loaded.contentType() != null && !loaded.contentType().isBlank() ? loaded.contentType() : "image/png";
            return new MediaReference(null, "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(loaded.data()));
        }
        return reference;
    }

    private MediaReference mediaReference(ExecutionContext.AttachedContent content) {
        if (content.isBase64()) {
            var mimeType = Strings.isBlank(content.mediaType) ? "image/png" : content.mediaType;
            return new MediaReference(null, "data:" + mimeType + ";base64," + content.data);
        }
        return new MediaReference(content.url, null);
    }

    private String defaultModel(ExecutionContext context) {
        var model = context.getCustomVariables().get("media.video.model");
        return model instanceof String value && !value.isBlank() ? value : null;
    }

    private String pendingVideoTask(ExecutionContext context) {
        var value = context.getCustomVariable(PENDING_VIDEO_TASK_CONTEXT_KEY);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private int incrementFailCount(ExecutionContext context) {
        var next = failCount(context) + 1;
        context.getCustomVariables().put(VIDEO_SUBMIT_FAIL_COUNT_CONTEXT_KEY, next);
        return next;
    }

    private int failCount(ExecutionContext context) {
        var value = context.getCustomVariable(VIDEO_SUBMIT_FAIL_COUNT_CONTEXT_KEY);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private void resetFailCount(ExecutionContext context) {
        context.getCustomVariables().remove(VIDEO_SUBMIT_FAIL_COUNT_CONTEXT_KEY);
    }

    // video submission mutates per-session pending state; never run two submissions concurrently
    @Override
    public boolean isConcurrencySafe(String arguments) {
        return false;
    }

    @Override
    public ToolCallResult poll(String taskId) {
        if (mediaProvider == null) return ToolCallResult.failed("media provider not configured — video polling unavailable");
        try {
            var status = mediaProvider.getVideoStatus(taskId);
            return switch (status.status()) {
                case "completed" -> ToolCallResult.completed(
                        "Video " + taskId + " is ready. Call get_video_status with this video_id to save it locally.");
                case "failed" -> ToolCallResult.failed(
                        "Video generation failed: " + (status.error() != null ? status.error() : "unknown error"));
                default -> {
                    var progress = status.progress() != null ? status.progress() + "%" : "unknown";
                    yield ToolCallResult.pending(taskId,
                            "Video is still processing (progress: " + progress + "). Poll again.");
                }
            };
        } catch (Exception e) {
            return ToolCallResult.failed("Video polling failed: " + e.getMessage(), e);
        }
    }

    interface ReferenceImageLoader {
        LoadedImage load(String url);

        record LoadedImage(byte[] data, String contentType) {
        }
    }

    static final class HTTPReferenceImageLoader implements ReferenceImageLoader {
        private final HTTPClient client = new PatchedHTTPClientBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(30))
                .trustAll()
                .build();

        @Override
        public LoadedImage load(String url) {
            var response = client.execute(new HTTPRequest(HTTPMethod.GET, url));
            if (response.statusCode < 200 || response.statusCode >= 300) {
                throw new IllegalArgumentException("failed to download reference image: HTTP " + response.statusCode);
            }
            if (response.body.length > MAX_REFERENCE_IMAGE_BYTES) {
                throw new IllegalArgumentException("reference image too large: " + response.body.length + " bytes (max " + MAX_REFERENCE_IMAGE_BYTES + ")");
            }
            var contentType = response.headers == null ? null : response.headers.get("Content-Type");
            return new LoadedImage(response.body, contentType);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, GenerateVideoTool> {
        private MediaProvider mediaProvider;
        private ReferenceImageLoader referenceImageLoader = new HTTPReferenceImageLoader();
        // tracks whether a custom description was set; the parent field is kept in sync via super
        private boolean customDescriptionSet;

        public Builder mediaProvider(MediaProvider mediaProvider) {
            this.mediaProvider = mediaProvider;
            return this;
        }

        @Override
        public Builder description(String description) {
            customDescriptionSet = true;
            super.description(description);
            return this;
        }

        Builder referenceImageLoader(ReferenceImageLoader referenceImageLoader) {
            this.referenceImageLoader = referenceImageLoader;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public GenerateVideoTool build() {
            this.name(TOOL_NAME);
            if (!customDescriptionSet) super.description(TOOL_DESC);
            this.timeoutMs(120_000L); // submit should be fast — video generation is async
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "A detailed text description of the video scene").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "The video generation model to use (uses the default if omitted); must be a model configured in the gateway — do not guess"),
                    ToolCallParameters.ParamSpec.of(String.class, "model_scope", "once (default) or session; session sets the model as the conversation default for subsequent calls, empty model clears it"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "seconds", "Video duration in seconds (optional; defaults to 10, provider maximum is 10)"),
                    ToolCallParameters.ParamSpec.of(String.class, "size", "Video dimensions, e.g. 1280x720"),
                    ToolCallParameters.ParamSpec.of(String.class, "input_references", "JSON array of reference images; each item is {\"b64Json\":\"data:image/jpeg;base64,...\"} (full data URL) or {\"url\":\"https://...\"} (server downloads it); omit to use attached images"),
                    ToolCallParameters.ParamSpec.of(String.class, "previous_video_id", "A gateway video ID to edit conversationally with a supported provider"),
                    ToolCallParameters.ParamSpec.of(String.class, "provider_extra", "Provider-specific JSON parameters")
            ));
            var tool = new GenerateVideoTool(mediaProvider, referenceImageLoader);
            build(tool);
            return tool;
        }
    }
}
