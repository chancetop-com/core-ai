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

import java.util.List;

/**
 * @author stephen
 */
public class CaptionImageTool extends ToolCall {
    public static final String TOOL_NAME = "caption_image";

    private static final String TOOL_DESC = """
            tool to generate a caption for an image based on the image content and a query.
            when to use:
                when you want to understand the content of an image, you can use this tool to generate a caption for the image based on the image content and a query.
                the query can be used to specify what kind of information you want to extract from the image.
                for example, if you want to know what is in the image, you can use "what is in the image?" as the query.
                if you want to know the relationship between objects in the image, you can use "what is the relationship between object A and object B?" as the query.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        var params = JsonUtil.fromJson(CaptionImageToolParams.class, text);
        var llmProvider = context.getLlmProvider();
        var messages = List.of(Message.of(new Message.MessageRecord(
                RoleType.USER,
                List.of(Content.of(params.query), Content.of(Content.ImageUrl.of(params.url, null))),
                null, null, null, null)));
        var effectiveModel = resolveModel(context, llmProvider);
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

    public static class CaptionImageToolParams {
        public String query;
        public String url;
    }
}
