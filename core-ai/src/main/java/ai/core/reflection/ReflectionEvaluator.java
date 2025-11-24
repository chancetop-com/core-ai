package ai.core.reflection;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.prompt.engines.MustachePromptTemplate;
import core.framework.crypto.Hash;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent utility class for evaluating agent solutions in reflection process.
 * This class does NOT create circular dependencies - it receives only necessary parameters
 * and calls LLM provider directly without depending on Agent object.
 *
 * @author xander
 */
public final class ReflectionEvaluator {

    /**
     * Evaluate agent output in independent LLM context.
     * This is a pure function that takes only necessary parameters.
     *
     * @param request the evaluation request containing all necessary parameters
     * @return evaluation result containing JSON and token usage
     */
    public static EvaluationResult evaluate(EvaluationRequest request) {
        // Build evaluator system prompt with task context and criteria
        String evaluatorSystemPrompt = buildEvaluatorPrompt(
                request.originalInput(),
                request.config().prompt(),
                request.config().evaluationCriteria(),
                request.variables()
        );

        // Build evaluation user message (the solution to evaluate)
        String evaluationUserMessage = buildEvaluationUserMessage(request.currentOutput());

        // Create independent message list for evaluation
        List<Message> evaluationMessages = List.of(
                Message.of(RoleType.SYSTEM, evaluatorSystemPrompt, request.agentName() + "-evaluator"),
                Message.of(RoleType.USER, evaluationUserMessage, null, null, null, null)
        );

        // Call LLM in independent context with JSON response format
        CompletionRequest evalRequest = CompletionRequest.of(
                evaluationMessages,
                null,  // No tools
                request.temperature(),
                request.model(),
                request.agentName() + "-evaluator"
        );
        evalRequest.responseFormat = ResponseFormat.of(ReflectionEvaluation.class);

        // Direct LLM call without going through Agent execution
        CompletionResponse evalResponse = request.llmProvider().completion(evalRequest);

        // Return both evaluation JSON and token usage for caller to handle
        return new EvaluationResult(
                evalResponse.choices.getFirst().message.content,
                evalResponse.usage
        );
    }

    /**
     * Build improvement prompt based on evaluation feedback.
     * This prompt will be used to regenerate the solution.
     *
     * @param evaluationText the raw evaluation text/JSON
     * @param evaluation parsed evaluation object
     * @return improvement prompt for agent
     */
    public static String buildImprovementPrompt(String evaluationText, ReflectionEvaluation evaluation) {
        StringBuilder prompt = new StringBuilder(256);

        prompt.append("Based on the evaluation feedback, please improve your solution.\n\n**Evaluation Feedback:**\n")
                .append(evaluationText).append("\n\n");

        if (!evaluation.getWeaknesses().isEmpty()) {
            prompt.append("**Key Issues to Address:**\n");
            for (String weakness : evaluation.getWeaknesses()) {
                prompt.append("- ").append(weakness).append('\n');
            }
            prompt.append('\n');
        }

        if (!evaluation.getSuggestions().isEmpty()) {
            prompt.append("**Improvement Suggestions:**\n");
            for (String suggestion : evaluation.getSuggestions()) {
                prompt.append("- ").append(suggestion).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("Please provide an improved solution that addresses these points.");

        return prompt.toString();
    }

    private static String buildEvaluatorPrompt(String originalTask, String promptTemplate,
                                               String evaluationCriteria, Map<String, Object> variables) {
        // If no evaluation criteria, return prompt as-is
        if (evaluationCriteria == null || evaluationCriteria.isEmpty()) {
            return promptTemplate;
        }

        // Build context for template rendering
        Map<String, Object> evalContext = new HashMap<>(variables != null ? variables : Map.of());
        evalContext.put("task", originalTask);
        evalContext.put("evaluationCriteria", evaluationCriteria);

        // Render template with Mustache
        return new MustachePromptTemplate().execute(
                promptTemplate,
                evalContext,
                Hash.md5Hex(promptTemplate)
        );
    }

    private static String buildEvaluationUserMessage(String solution) {
        return String.format("""
                **Solution to Evaluate:**

                %s

                Please provide your evaluation in the JSON format specified in the system prompt.
                """, solution);
    }

    private ReflectionEvaluator() {
        // Utility class, no instantiation
    }

    /**
     * Parameters for evaluation request.
     */
    public record EvaluationRequest(
            String originalInput,
            String currentOutput,
            String agentName,
            LLMProvider llmProvider,
            Double temperature,
            String model,
            ReflectionConfig config,
            Map<String, Object> variables
    ) { }

    /**
     * Result of evaluation containing both the evaluation JSON and token usage.
     */
    public record EvaluationResult(String evaluationJson, Usage usage) { }
}
