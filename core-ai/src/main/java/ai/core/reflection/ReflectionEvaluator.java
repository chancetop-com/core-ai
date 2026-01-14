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
 * @author xander
 */
public final class ReflectionEvaluator {

    public static EvaluationResult evaluate(EvaluationRequest request) {
        String evaluatorSystemPrompt = buildEvaluatorPrompt(
                request.originalInput(),
                request.config().prompt(),
                request.config().evaluationCriteria(),
                request.variables()
        );

        String evaluationUserMessage = buildEvaluationUserMessage(request.currentOutput());

        List<Message> evaluationMessages = List.of(
                Message.of(RoleType.SYSTEM, evaluatorSystemPrompt, request.agentName() + "-evaluator"),
                Message.of(RoleType.USER, evaluationUserMessage, null, null, null, null)
        );

        CompletionRequest evalRequest = CompletionRequest.of(
                evaluationMessages,
                null,  // No tools
                request.temperature(),
                request.model(),
                request.agentName() + "-evaluator"
        );
        evalRequest.responseFormat = ResponseFormat.jsonObject();

        CompletionResponse evalResponse = request.llmProvider().completion(evalRequest);

        return new EvaluationResult(
                evalResponse.choices.getFirst().message.content,
                evalResponse.usage
        );
    }

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
        if (evaluationCriteria == null || evaluationCriteria.isEmpty()) {
            return promptTemplate;
        }

        Map<String, Object> evalContext = new HashMap<>(variables != null ? variables : Map.of());
        evalContext.put("task", originalTask);
        evalContext.put("evaluationCriteria", evaluationCriteria);

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
    }

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

    public record EvaluationResult(String evaluationJson, Usage usage) { }
}
