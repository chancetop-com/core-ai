package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
            Pass urls (array) instead of url to inspect several images in one call, e.g. to compare them.
            Pass context with a short task background when the question depends on the wider conversation.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        var params = JsonUtil.fromJson(CaptionImageToolParams.class, text);
        var llmProvider = context.getLlmProvider();
        var urls = resolveUrls(params);
        var contents = new ArrayList<Content>(urls.size() + 1);
        contents.add(Content.of(buildQueryText(params)));
        urls.stream().map(url -> Content.of(resolveImageUrl(url, context))).forEach(contents::add);
        var messages = List.of(Message.of(new Message.MessageRecord(
                RoleType.USER,
                List.copyOf(contents),
                null, null, null, null)));
        var effectiveModel = resolveModel(context);
        LOGGER.info("caption_image using model=[{}], context.multiModalModel=[{}], context.model=[{}]",
                effectiveModel, context.getMultiModalModel(), context.getModel());
        var rsp = llmProvider.completion(CompletionRequest.of(messages, List.of(), null, effectiveModel, null));
        return ToolCallResult.completed(rsp.choices.getFirst().message.content);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("CAPTION_IMAGE_TOOL_FAILED", "CaptionImageTool requires ExecutionContext");
    }

    private List<String> resolveUrls(CaptionImageToolParams params) {
        if (params.urls() != null && !params.urls().isEmpty()) return params.urls();
        if (params.url() != null && !params.url().isBlank()) return List.of(params.url());
        throw new AgentRuntimeException("CAPTION_IMAGE_TOOL_FAILED", "url or urls is required");
    }

    private String buildQueryText(CaptionImageToolParams params) {
        if (params.context() == null || params.context().isBlank()) return params.query();
        return params.query() + "\nTask context: " + params.context();
    }

    private String resolveModel(ExecutionContext context) {
        if (context.getMultiModalModel() != null) return context.getMultiModalModel();
        var captionModel = context.getCustomVariable("media.caption.model");
        if (captionModel instanceof String value && !value.isBlank()) return value;
        // never fall back to the main/provider model: it may be text-only and would fail on image input
        throw new AgentRuntimeException("CAPTION_IMAGE_NO_VISION_MODEL",
                "no vision-capable model configured: set agent multiModalModel or media.caption.model");
    }

    private Content.ImageUrl resolveImageUrl(String url, ExecutionContext context) {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
            return Content.ImageUrl.of(url, null);
        }
        // If a sandbox is available, try to download the file from the sandbox first.
        // The file may reside in the sandbox filesystem (e.g., /tmp/screenshot.png) rather than
        // on the server filesystem.
        var sandbox = context.getSandbox();
        if (sandbox != null) {
            try {
                var sandboxFile = sandbox.downloadFile(url);
                if (sandboxFile != null && sandboxFile.path() != null) {
                    byte[] bytes = Files.readAllBytes(sandboxFile.path());
                    String mimeType = sandboxFile.contentType();
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    String dataUri = "data:" + mimeType + ";base64," + base64;
                    LOGGER.info("caption_image resolved sandbox file [{}] with mimeType=[{}], size=[{}] bytes",
                            url, mimeType, bytes.length);
                    return Content.ImageUrl.of(dataUri, mimeType);
                }
            } catch (Exception e) {
                LOGGER.warn("caption_image failed to download from sandbox, falling back to local filesystem: url={}, error={}",
                        url, e.getMessage());
            }
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
                    "Failed to read image file: " + url + " - " + e.getMessage(), e);
        }
    }

    private String detectMimeType(Path path) {
        var fileNamePath = path.getFileName();
        if (fileNamePath == null) return "application/octet-stream";
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
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
                    ToolCallParameters.ParamSpec.of(String.class, "url", "url of the image"),
                    ToolCallParameters.ParamSpec.of(List.class, "urls", "urls of multiple images to inspect together (e.g. for comparison); use either url or urls"),
                    ToolCallParameters.ParamSpec.of(String.class, "context", "optional task background so the vision model answers in context")
            ));
            var tool = new CaptionImageTool();
            build(tool);
            return tool;
        }
    }

    public record CaptionImageToolParams(String query, String url, List<String> urls, String context) {
    }
}
