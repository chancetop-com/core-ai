package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;
import java.util.function.Function;

/**
 * Tool that allows the agent to ask the user a question and receive a response.
 *
 * @author xander
 */
public class AskUserTool extends ToolCall {

    public static final String TOOL_NAME = "ask_user";

    private static final String TOOL_DESC = """
            Ask the user a question and wait for their response.
            Use this when you need clarification, confirmation, or additional information
            from the user before proceeding with a task.

            Examples:
            - Ask for confirmation before making destructive changes
            - Ask for preferences when multiple valid approaches exist
            - Ask for missing information needed to complete a task
            """;

    public static Builder builder() {
        return new Builder();
    }

    private Function<String, String> questionHandler;

    public void setQuestionHandler(Function<String, String> handler) {
        this.questionHandler = handler;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, arguments);
            var question = (String) argsMap.get("question");
            if (question == null || question.isBlank()) {
                return ToolCallResult.failed("Error: 'question' parameter is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            if (questionHandler == null) {
                return ToolCallResult.failed("Error: No question handler configured")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            String answer = questionHandler.apply(question);
            if (answer == null) {
                answer = "(no response)";
            }
            return ToolCallResult.completed("User response: " + answer)
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to ask user: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, AskUserTool> {
        private Function<String, String> questionHandler;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder questionHandler(Function<String, String> handler) {
            this.questionHandler = handler;
            return this;
        }

        public AskUserTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "question",
                            "The question to ask the user. Be clear and specific.").required()
            ));
            var tool = new AskUserTool();
            build(tool);
            tool.questionHandler = this.questionHandler;
            return tool;
        }
    }
}
