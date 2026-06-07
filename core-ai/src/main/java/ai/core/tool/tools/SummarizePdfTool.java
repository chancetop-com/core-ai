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
import java.util.Base64;
import java.util.List;

/**
 * Tool to summarize or analyze PDF documents from URL.
 *
 * @author xander
 */
public class SummarizePdfTool extends ToolCall {
    public static final String TOOL_NAME = "summarize_pdf";

    private static final Logger LOGGER = LoggerFactory.getLogger(SummarizePdfTool.class);

    private static final String TOOL_DESC = """
            Tool to read and analyze PDF documents.
            IMPORTANT: You MUST use this tool (NOT read_file) whenever the user provides a PDF file or URL, or asks about a PDF document.
            read_file can only display PDF bytes but cannot actually understand PDF content. Only summarize_pdf sends the PDF to a model that can read and analyze it.
            The query parameter specifies what you want to know about the PDF (e.g., "summarize this document", "extract key points", "what is this about?").
            The url parameter supports HTTP/HTTPS URLs and local file paths.
            """;

    public static Builder builder() {
        return new Builder();
    }

    private String model;

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        var params = JsonUtil.fromJson(SummarizePdfToolParams.class, text);
        var llmProvider = context.getLlmProvider();
        var fileContent = resolveFileContent(params.url());
        var messages = List.of(Message.of(new Message.MessageRecord(
                RoleType.USER,
                List.of(Content.of(params.query()), fileContent),
                null, null, null, null)));
        var targetModel = resolveModel(context);
        var rsp = llmProvider.completion(CompletionRequest.of(messages, List.of(), null, targetModel, null));
        return ToolCallResult.completed(rsp.choices.getFirst().message.content);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("SUMMARIZE_PDF_TOOL_FAILED", "SummarizePdfTool requires ExecutionContext");
    }

    private String resolveModel(ExecutionContext context) {
        if (model != null) return model;
        if (context.getMultiModalModel() != null) return context.getMultiModalModel();
        if (context.getModel() != null) return context.getModel();
        return context.getLlmProvider().config.getModel();
    }

    private Content resolveFileContent(String url) {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
            return Content.ofFileUrl(url);
        }
        Path path = Paths.get(url);
        if (!Files.isRegularFile(path)) {
            throw new AgentRuntimeException("SUMMARIZE_PDF_TOOL_FAILED",
                    "PDF file not found: " + url + ". Provide a valid HTTP URL or local file path.");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String filename = path.getFileName().toString();
            LOGGER.info("summarize_pdf resolved local file [{}], size=[{}] bytes", url, bytes.length);
            return Content.ofFileBase64(base64, "application/pdf", filename);
        } catch (IOException e) {
            throw new AgentRuntimeException("SUMMARIZE_PDF_TOOL_FAILED",
                    "Failed to read PDF file: " + url + " - " + e.getMessage(), e);
        }
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

    public record SummarizePdfToolParams(String query, String url) {
    }
}
