package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class CaptionImageTool extends ToolCall {
    public static final String TOOL_NAME = "caption_image";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptionImageTool.class);

    private static final Map<String, String> EXTENSION_TO_MIME = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "tiff", "image/tiff",
            "tif", "image/tiff"
    );

    private static final String TOOL_DESC = """
            Use this tool to analyze and understand the content of an image using AI vision.
            IMPORTANT: Always use this tool (NOT read_file) when the user asks you to:
            - "read", "view", "look at", "check", "analyze", or "describe" an image/picture/photo/screenshot
            - extract text, data, or information from an image
            - explain what an image shows or contains
            - answer any question about what is in an image
            read_file can only display image bytes but cannot actually understand image content. Only caption_image sends the image to a vision model that can see and interpret what's in it.
            The query parameter specifies what you want to know about the image (e.g., "what does this image say?", "describe the chart", "extract the text").
            The url parameter supports HTTP/HTTPS URLs, data URIs, and local file paths.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        var params = JsonUtil.fromJson(CaptionImageToolParams.class, text);
        var llmProvider = context.getLlmProvider();
        var imageUrl = resolveImageUrl(params.url());
        var messages = List.of(Message.of(new Message.MessageRecord(
                RoleType.USER,
                List.of(Content.of(params.query()), Content.of(imageUrl)),
                null, null, null, null)));
        var effectiveModel = resolveModel(context, llmProvider);
        LOGGER.info("caption_image using model=[{}], context.multiModalModel=[{}], context.model=[{}]",
                effectiveModel, context.getMultiModalModel(), context.getModel());
        var rsp = llmProvider.completion(CompletionRequest.of(messages, List.of(), null, effectiveModel, null));
        return ToolCallResult.completed(rsp.choices.getFirst().message.content);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("CAPTION_IMAGE_TOOL_FAILED", "CaptionImageTool requires ExecutionContext");
    }

    private String resolveModel(ExecutionContext context, LLMProvider llmProvider) {
        if (context.getMultiModalModel() != null) return context.getMultiModalModel();
        if (context.getModel() != null) return context.getModel();
        return llmProvider.config.getModel();
    }

    private Content.ImageUrl resolveImageUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
            return Content.ImageUrl.of(url, null);
        }
        Path path = Paths.get(url);
        if (!Files.isRegularFile(path)) {
            throw new AgentRuntimeException("CAPTION_IMAGE_TOOL_FAILED",
                    "Image file not found: " + url + ". Provide a valid HTTP URL or local file path.");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = detectMimeType(path);
            String dataUri = "data:" + mimeType + ";base64," + base64;
            LOGGER.info("caption_image resolved local file [{}] with mimeType=[{}], size=[{}] bytes", url, mimeType, bytes.length);
            return Content.ImageUrl.of(dataUri, mimeType);
        } catch (IOException e) {
            throw new AgentRuntimeException("CAPTION_IMAGE_TOOL_FAILED",
                    "Failed to read image file: " + url + " - " + e.getMessage());
        }
    }

    private String detectMimeType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            String extension = fileName.substring(dotIndex + 1);
            String mimeType = EXTENSION_TO_MIME.get(extension);
            if (mimeType != null) return mimeType;
        }
        return "application/octet-stream";
    }

    public static class Builder extends ToolCall.Builder<Builder, CaptionImageTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public CaptionImageTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "query", "query").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "url", "url of the image").required()
            ));
            var tool = new CaptionImageTool();
            build(tool);
            return tool;
        }
    }

    public record CaptionImageToolParams(String query, String url) {
    }
}
