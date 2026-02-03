package ai.core.agent.internal;

import ai.core.agent.ExecutionContext;
import ai.core.agent.streaming.DefaultStreamingCallback;
import ai.core.agent.streaming.StreamingCallback;
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
import core.framework.util.Strings;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentHelper {

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
        return buildToolMessage(tool, result, false);
    }

    public static Message buildToolMessage(FunctionCall tool, ToolCallResult result, boolean isDirectReturn) {
        return switch (result.getType()) {
            case TEXT -> Message.of(RoleType.TOOL, buildTextContent(result, isDirectReturn), tool.function.name, tool.id, null, null);
            case IMAGE -> Message.of(new Message.MessageRecord(RoleType.TOOL, buildImageContent(result), "", tool.function.name, tool.id, null, null));
        };
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
        return buildUserMessage(query, context.getAttachedContent());
    }

    public static Message buildUserMessage(String query, ExecutionContext.AttachedContent attachedContent) {
        if (attachedContent == null) {
            return Message.of(RoleType.USER, query, buildRequestName(false), null, null, null);
        }
        return Message.of(new Message.MessageRecord(
            RoleType.USER,
            List.of(Content.of(query), buildAttachedContent(attachedContent)),
            null,
            buildRequestName(false),
            null,
            null,
            null));
    }

    public static Content buildAttachedContent(ExecutionContext.AttachedContent attachedContent) {
        return switch (attachedContent.type) {
            case IMAGE -> buildImageAttachedContent(attachedContent);
            case PDF -> buildPdfAttachedContent(attachedContent);
        };
    }

    private static Content buildImageAttachedContent(ExecutionContext.AttachedContent attachedContent) {
        if (attachedContent.isBase64()) {
            var dataUri = Strings.format("data:{};base64,{}", attachedContent.mediaType, attachedContent.data);
            return Content.of(Content.ImageUrl.of(dataUri, attachedContent.mediaType));
        }
        return Content.of(Content.ImageUrl.of(attachedContent.url, null));
    }

    private static Content buildPdfAttachedContent(ExecutionContext.AttachedContent attachedContent) {
        if (attachedContent.isBase64()) {
            var filename = attachedContent.filename != null ? attachedContent.filename : "document.pdf";
            return Content.ofFileBase64(attachedContent.data, attachedContent.mediaType, filename);
        }
        return Content.ofFileUrl(attachedContent.url);
    }
}
