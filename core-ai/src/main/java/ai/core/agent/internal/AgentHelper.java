package ai.core.agent.internal;

import ai.core.agent.ExecutionContext;
import ai.core.llm.InputModality;
import ai.core.llm.ModalitySupport;
import ai.core.llm.ModelModalityRegistry;
import ai.core.llm.streaming.DefaultStreamingCallback;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.prompt.Prompts;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionEvaluation;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.CaptionImageTool;
import ai.core.tool.tools.GenerateImageTool;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentHelper.class);

    public static List<Tool> toReqTools(List<ToolCall> toolCalls) {
        return toolCalls.stream().filter(ToolCall::isLlmVisible).map(ToolCall::toTool).toList();
    }

    public static String specialReminder(String toolName, String toolResult) {
        return Prompts.TOOL_DIRECT_RETURN_REMINDER_PROMPT.formatted(toolName, toolResult);
    }

    public static StreamingCallback elseDefaultCallback(StreamingCallback streamingCallback) {
        return streamingCallback == null ? new DefaultStreamingCallback() : streamingCallback;
    }

    public static boolean lastIsToolMsg(List<Message> messages) {
        return RoleType.TOOL == messages.getLast().role;
    }

    public static boolean isValidEvaluation(ReflectionEvaluation evaluation) {
        return evaluation.getScore() >= 1 && evaluation.getScore() <= 10;
    }

    public static String buildRequestName(boolean isToolCall) {
        return isToolCall ? "tool" : "user";
    }

    public static boolean shouldTerminateReflection(ReflectionConfig reflectionConfig, ReflectionEvaluation eval, int round) {
        if (eval.isPass() && eval.getScore() >= ReflectionConfig.DEFAULT_REFLECTION_CONTINUE_SCORE) return true;
        if (!eval.isShouldContinue()) return true;
        return round >= reflectionConfig.minRound() && eval.getScore() >= ReflectionConfig.DEFAULT_REFLECTION_CONTINUE_SCORE;
    }

    public static String generateToolCallId() {
        return "slash_command_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static Message buildToolMessage(FunctionCall tool, ToolCallResult result) {
        return buildToolMessage(tool, result, false, null);
    }

    public static Message buildToolMessage(FunctionCall tool, ToolCallResult result, boolean isDirectReturn) {
        return buildToolMessage(tool, result, isDirectReturn, null);
    }

    public static Message buildToolMessage(FunctionCall tool, ToolCallResult result, boolean isDirectReturn, ExecutionContext context) {
        return switch (result.getType()) {
            case TEXT -> Message.of(RoleType.TOOL, buildTextContent(result, isDirectReturn), tool.function.name, tool.id, null);
            case IMAGE -> context != null && !context.isVisionNative()
                    ? Message.of(RoleType.TOOL, buildImageReference(tool, result, context), tool.function.name, tool.id, null)
                    : Message.of(new Message.MessageRecord(RoleType.TOOL, buildImageContent(result), "", tool.function.name, tool.id, null));
        };
    }

    public static boolean resolveVisionNative(String model, String multiModalModel, ModelModalityRegistry registry) {
        if (registry.supports(model, InputModality.IMAGE) != ModalitySupport.UNSUPPORTED) return true;
        return multiModalModel != null && registry.supports(multiModalModel, InputModality.IMAGE) != ModalitySupport.UNSUPPORTED;
    }

    // a natively-seeing model misuses a redundant caption tool; hide it and let images flow inline
    public static List<Tool> filterRedundantVisionTools(List<Tool> tools, boolean visionNative) {
        if (!visionNative || tools == null) return tools;
        return tools.stream()
                .filter(tool -> tool.function == null || !CaptionImageTool.TOOL_NAME.equals(tool.function.name))
                .toList();
    }

    // text-model path: persist the image and hand the model a reference it can inspect via caption_image
    private static String buildImageReference(FunctionCall tool, ToolCallResult result, ExecutionContext context) {
        var url = persistImage(result.getImageBase64(), result.getImageFormat(), context);
        if (url != null) {
            return Strings.format("[Image result: {}] The current model cannot view images directly. Call caption_image with this url to inspect it.", url);
        }
        return Strings.format("[Image result from tool {}] The current model cannot view images directly. Call caption_image with the original image path or url to inspect it.", tool.function.name);
    }

    private static String persistImage(String base64, String format, ExecutionContext context) {
        var sink = context.getCustomVariable(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, GenerateImageTool.ImageOutputSink.class);
        if (sink == null) return null;
        try {
            var extension = format != null && format.contains("/") ? format.substring(format.indexOf('/') + 1) : "png";
            var contentType = format != null && format.contains("/") ? format : "image/" + extension;
            var bytes = Base64.getDecoder().decode(base64);
            return sink.save("tool-image-" + UUID.randomUUID() + "." + extension, contentType, bytes);
        } catch (Exception e) {
            LOGGER.warn("failed to persist image, falling back to reference without url", e);
            return null;
        }
    }

    private static String buildTextContent(ToolCallResult result, boolean isDirectReturn) {
        if (isDirectReturn) {
            return specialReminder(result.getToolName(), result.toResultForLLM());
        } else {
            return result.toResultForLLM();
        }
    }

    private static List<Content> buildImageContent(ToolCallResult result) {
        return List.of(Content.of(Prompts.IMAGE_CAPTIONING_PROMPT), Content.of(Content.ImageUrl.of(buildImageUrl(result), result.getImageFormat())));
    }

    private static String buildImageUrl(ToolCallResult result) {
        return Strings.format("data:{};base64,{}", result.getImageFormat(), result.getImageBase64());
    }

    public static Message buildUserMessage(String query, ExecutionContext context) {
        var attachedContents = context.getAttachedContents();
        if (attachedContents == null || attachedContents.isEmpty()) {
            return Message.of(RoleType.USER, query, buildRequestName(false), null, null);
        }
        var contents = new java.util.ArrayList<Content>(attachedContents.size() + 1);
        contents.add(Content.of(query));
        attachedContents.stream()
                .filter(attachedContent -> attachedContent.type != ExecutionContext.AttachedContent.AttachedContentType.VIDEO)
                .map(attachedContent -> buildAttachedContent(attachedContent, context))
                .forEach(contents::add);
        attachedContents.stream()
                .filter(attachedContent -> attachedContent.type == ExecutionContext.AttachedContent.AttachedContentType.VIDEO)
                .map(AgentHelper::buildVideoReferenceHint)
                .map(Content::of)
                .forEach(contents::add);
        return Message.of(new Message.MessageRecord(
            RoleType.USER,
            contents,
            null,
            buildRequestName(false),
            null,
            null));
    }

    public static Message buildUserMessage(String query, ExecutionContext.AttachedContent attachedContent) {
        if (attachedContent == null) {
            return Message.of(RoleType.USER, query, buildRequestName(false), null, null);
        }
        return Message.of(new Message.MessageRecord(
            RoleType.USER,
            List.of(Content.of(query), buildAttachedContent(attachedContent)),
            null,
            buildRequestName(false),
            null,
            null));
    }

    private static String buildVideoReferenceHint(ExecutionContext.AttachedContent attachedContent) {
        var name = attachedContent.filename != null ? attachedContent.filename : "video";
        return "[Video attachment: " + name + "]\n"
                + "reference: " + attachedContent.url + "\n"
                + "The video content cannot be read or downloaded directly. To answer any question about this video, "
                + "you MUST call the understand_video tool with attachment_reference_id=\"" + attachedContent.url + "\".";
    }

    public static Content buildAttachedContent(ExecutionContext.AttachedContent attachedContent) {
        return buildAttachedContent(attachedContent, null);
    }

    private static Content buildAttachedContent(ExecutionContext.AttachedContent attachedContent, ExecutionContext context) {
        return switch (attachedContent.type) {
            case IMAGE -> buildImageAttachedContent(attachedContent, context);
            case PDF -> buildPdfAttachedContent(attachedContent);
            case VIDEO -> throw new IllegalStateException("video references must be resolved by understand_video tool");
        };
    }

    private static Content buildImageAttachedContent(ExecutionContext.AttachedContent attachedContent, ExecutionContext context) {
        if (!attachedContent.isBase64()) return Content.of(Content.ImageUrl.of(attachedContent.url, null));
        var referenceUrl = captionPathUrl(attachedContent, context);
        if (referenceUrl != null) {
            return Content.of(Strings.format("[Image attachment: {}] The current model cannot view images directly. Call caption_image with this url to inspect it.", referenceUrl));
        }
        var dataUri = Strings.format("data:{};base64,{}", attachedContent.mediaType, attachedContent.data);
        return Content.of(Content.ImageUrl.of(dataUri, attachedContent.mediaType));
    }

    private static String captionPathUrl(ExecutionContext.AttachedContent attachedContent, ExecutionContext context) {
        if (context == null || context.isVisionNative()) return null;
        return persistImage(attachedContent.data, attachedContent.mediaType, context);
    }

    private static Content buildPdfAttachedContent(ExecutionContext.AttachedContent attachedContent) {
        if (attachedContent.isBase64()) {
            var filename = attachedContent.filename != null ? attachedContent.filename : "document.pdf";
            return Content.ofFileBase64(attachedContent.data, attachedContent.mediaType, filename);
        }
        return Content.ofFileUrl(attachedContent.url);
    }
}
