package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;

/**
 * @author stephen
 */
public final class UnderstandVideoTool extends ToolCall {
    public static final String TOOL_NAME = "understand_video";

    private static final String DESCRIPTION = """
            Understand the content of a user-uploaded video. When the user has uploaded a video and asks what it
            shows, asks to summarize it, asks questions about it, or wants facts from it, you MUST call this tool —
            the video content is ONLY accessible through this tool.
            IMPORTANT:
            - Never try to download, read, open, or analyze the video file with other tools (read_file, shell,
              web_fetch, etc.). The raw video bytes cannot be interpreted without this tool.
            - attachment_reference_id must be the reference value shown in the user message (e.g. video_xxx).
              Do not pass a URL, blob path, Gemini URI, or base64 data.
            - Do not call this tool merely because a video was uploaded; only call it when the user's question
              actually requires understanding the video content.
            """;

    private final VideoUnderstandingService service;

    private UnderstandVideoTool(VideoUnderstandingService service) {
        this.service = service;
    }

    public static Builder builder(VideoUnderstandingService service) {
        return new Builder().service(service);
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
            if (!isVisibleReference(context, referenceId)) {
                return ToolCallResult.failed("attachment_reference_id is not available in the current context");
            }
            var model = getStringValue(params, "model");
            if (Strings.isBlank(model)) model = context.getMultiModalModel();
            var result = service.understand(new AttachmentOwner(context.getUserId(), context.getSessionId()),
                    referenceId, model, question);
            return ToolCallResult.completed(result.answer())
                    .withStats("model", result.model())
                    .withStats("file_cache", result.fileCache())
                    .withStats("prompt_tokens", result.promptTokens())
                    .withStats("completion_tokens", result.completionTokens())
                    .withStats("total_tokens", result.totalTokens());
        } catch (Exception e) {
            return ToolCallResult.failed("Video understanding failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private boolean isVisibleReference(ExecutionContext context, String referenceId) {
        var attachments = context.getAttachedContents();
        if (attachments == null || attachments.isEmpty()) return true; // follow-up turn: service validates owner
        return attachments.stream().anyMatch(content ->
                content.type == ExecutionContext.AttachedContent.AttachedContentType.VIDEO
                        && referenceId.equals(content.url));
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
                            "The reference value shown in the user message (e.g. video_xxx). Required.").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "question", "Question to ask about the video").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "Optional video understanding model")
            ));
            var tool = new UnderstandVideoTool(service);
            build(tool);
            return tool;
        }
    }
}
