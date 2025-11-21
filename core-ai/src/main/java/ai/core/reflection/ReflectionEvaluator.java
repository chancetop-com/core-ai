package ai.core.reflection;

import ai.core.agent.Agent;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.engines.MustachePromptTemplate;
import core.framework.crypto.Hash;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent utility class for evaluating agent solutions in reflection process.
 * This class does NOT create circular dependencies - it only reads agent state
 * and calls LLM provider directly without calling back into Agent execution methods.
 *
 * @author xander
 */
public final class ReflectionEvaluator {

    /**
     * Evaluate agent output in independent LLM context.
     * This is a pure function that only reads agent state and makes an LLM call.
     *
     * @param agent the agent whose output will be evaluated (read-only access)
     * @param config reflection configuration
     * @param variables template variables for prompt rendering
     * @return evaluation result as JSON string
     */
    public static String evaluate(Agent agent, ReflectionConfig config, Map<String, Object> variables) {
        // Build evaluator system prompt with task context and criteria
        String evaluatorSystemPrompt = buildEvaluatorPrompt(
                agent.getInput(),
                config.prompt(),
                config.evaluationCriteria(),
                variables
        );

        // Build evaluation user message (the solution to evaluate)
        String evaluationUserMessage = buildEvaluationUserMessage(agent.getOutput());

        // Create independent message list for evaluation (not using Agent's message history)
        List<Message> evaluationMessages = List.of(
                Message.of(RoleType.SYSTEM, evaluatorSystemPrompt, agent.getName() + "-evaluator"),
                Message.of(RoleType.USER, evaluationUserMessage, null, null, null, null)
        );

        // Call LLM in independent context with JSON response format
        CompletionRequest evalRequest = CompletionRequest.of(
                evaluationMessages,
                null,  // No tools
                agent.getTemperature(),
                agent.getModel(),
                agent.getName() + "-evaluator"
        );
        evalRequest.responseFormat = ResponseFormat.json();

        // Direct LLM call without going through Agent execution
        CompletionResponse evalResponse = agent.getLLMProvider().completion(evalRequest);

        // Track token usage in agent
        agent.addTokenCost(evalResponse.usage);

        return evalResponse.choices.getFirst().message.content;
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
}
