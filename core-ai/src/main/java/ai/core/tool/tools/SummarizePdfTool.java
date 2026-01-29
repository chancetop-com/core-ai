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

import java.util.List;

/**
 * Tool to summarize or analyze PDF documents from URL.
 *
 * @author xander
 */
public class SummarizePdfTool extends ToolCall {
    public static final String TOOL_NAME = "summarize_pdf";

    private static final String TOOL_DESC = """
            Tool to read and analyze PDF documents from a URL.
            IMPORTANT: You MUST use this tool whenever the user provides a PDF URL or asks about a PDF document.
            This tool can read the PDF content and answer questions about it.
            """;

    public static Builder builder() {
        return new Builder();
    }

    private String model;

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        var params = JsonUtil.fromJson(SummarizePdfToolParams.class, text);
        var llmProvider = context.getLlmProvider();
        var messages = List.of(Message.of(new Message.MessageRecord(
                RoleType.USER,
                List.of(Content.of(params.query), Content.ofFileUrl(params.url)),
                null, null, null, null, null)));
        var targetModel = model != null ? model : context.getModel();
        var rsp = llmProvider.completion(CompletionRequest.of(messages, List.of(), null, targetModel, null));
        return ToolCallResult.completed(rsp.choices.getFirst().message.content);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("SUMMARIZE_PDF_TOOL_FAILED", "SummarizePdfTool requires ExecutionContext");
    }

    public static class Builder extends ToolCall.Builder<Builder, SummarizePdfTool> {
        private String model;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public SummarizePdfTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "query", "The question or instruction about the PDF content").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "url", "URL of the PDF document").required()
            ));
            var tool = new SummarizePdfTool();
            tool.model = this.model;
            build(tool);
            return tool;
        }
    }

    public static class SummarizePdfToolParams {
        public String query;
        public String url;
    }
}
