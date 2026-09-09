package ai.core.tool.tools;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Usage;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public final class UnderstandVideoTool extends ToolCall {
    public static final String TOOL_NAME = "understand_video";
    // "video_" plus one uuid hex group is the shortest abbreviation worth resolving
    private static final int MIN_PREFIX_LENGTH = "video_".length() + 8;

    private static final String DESCRIPTION = """
            Understand the content of a user-uploaded video. When the user has uploaded a video and asks what it
            shows, asks to summarize it, asks questions about it, or wants facts from it, you MUST call this tool —
            the video content is ONLY accessible through this tool.
            IMPORTANT:
            - Never try to download, read, open, or analyze the video file with other tools (read_file, shell,
              web_fetch, etc.). The raw video bytes cannot be interpreted without this tool.
            - attachment_reference_id must be the reference value shown in the user message or returned by another
              tool (e.g. video_reference_id from drama_list_takes), copied verbatim — the full video_<uuid>.
              Do not pass a URL, blob path, Gemini URI, or base64 data.
            - Do not call this tool merely because a video was uploaded; only call it when the user's question
              actually requires understanding the video content.
            """;

    public static Builder builder(VideoUnderstandingService service) {
        return new Builder().service(service);
    }

    private final VideoUnderstandingService service;

    private UnderstandVideoTool(VideoUnderstandingService service) {
        this.service = service;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("understand_video requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        if (service == null) return ToolCallResult.failed("video understanding service is not configured");
        try {
            var params = JsonUtil.toMap(arguments);
            var referenceId = getStringValue(params, "attachment_reference_id");
            var question = getStringValue(params, "question");
            if (Strings.isBlank(referenceId)) return ToolCallResult.failed("attachment_reference_id is required");
            if (Strings.isBlank(question)) return ToolCallResult.failed("question is required");
            var resolved = resolveVisibleReference(context, referenceId);
            if (resolved == null) {
                return ToolCallResult.failed("attachment_reference_id is not available in the current context: " + referenceId
                        + "; videos attached to this turn: " + visibleReferences(context));
            }
            referenceId = resolved;
            var model = getStringValue(params, "model");
            if (Strings.isBlank(model)) model = context.getMultiModalModel();
            var result = service.understand(new AttachmentOwner(context.getUserId(), context.getSessionId()),
                    referenceId, model, question);
            return ToolCallResult.completed(result.answer())
                    .withStats("model", result.model())
                    .withStats("file_cache", result.fileCache())
                    .withStats("prompt_tokens", result.promptTokens())
                    .withStats("completion_tokens", result.completionTokens())
                    .withStats("total_tokens", result.totalTokens())
                    .withLlmUsage(Strings.isBlank(result.model()) ? model : result.model(), new Usage((int) result.promptTokens(), (int) result.completionTokens(), (int) result.totalTokens()));
        } catch (Exception e) {
            return ToolCallResult.failed("Video understanding failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The attached video the id denotes: an exact match, or the single attached video whose id starts with it — models
     * routinely abbreviate {@code video_<uuid>} to its first hex group. Null when the id matches nothing or is ambiguous.
     * Without attachments on this turn the id passes through and the service validates ownership.
     */
    private String resolveVisibleReference(ExecutionContext context, String referenceId) {
        var videos = visibleReferences(context);
        if (videos.isEmpty()) return referenceId; // follow-up turn: service validates owner
        if (videos.contains(referenceId)) return referenceId;
        if (referenceId.length() < MIN_PREFIX_LENGTH) return null;
        var matches = videos.stream().filter(id -> id.startsWith(referenceId)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private List<String> visibleReferences(ExecutionContext context) {
        var attachments = context.getAttachedContents();
        if (attachments == null) return List.of();
        return attachments.stream()
                .filter(content -> content.type == AttachedContent.AttachedContentType.VIDEO && content.url != null)
                .map(content -> content.url).toList();
    }

    public interface VideoUnderstandingService {
        VideoUnderstandingResult understand(AttachmentOwner owner, String attachmentReferenceId,
                                            String effectiveModel, String question);
    }

    public record AttachmentOwner(String userId, String sessionId) { }

    public record VideoUnderstandingResult(String answer, String model, String fileCache,
                                           long promptTokens, long completionTokens, long totalTokens) {
        public VideoUnderstandingResult(String answer, String model, String fileCache) {
            this(answer, model, fileCache, 0, 0, 0);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, UnderstandVideoTool> {
        private VideoUnderstandingService service;

        public Builder service(VideoUnderstandingService service) {
            this.service = service;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public UnderstandVideoTool build() {
            name(TOOL_NAME);
            description(DESCRIPTION);
            timeoutMs(600_000L);
            parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "attachment_reference_id",
                            "The reference value shown in the user message or returned by a tool (full video_<uuid>, copied verbatim). Required.").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "question", "Question to ask about the video").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "Optional video understanding model")
            ));
            var tool = new UnderstandVideoTool(service);
            build(tool);
            return tool;
        }
    }
}
