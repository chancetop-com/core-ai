package ai.core.reflection;

/**
 * Reflection prompt templates for agent self-evaluation.
 *
 * @author stephen
 */
public class Reflection {
    /**
     * Default reflection template without evaluation criteria (backward compatible).
     * Uses simple termination-based approach.
     */
    public static final String DEFAULT_REFLECTION_CONTINUE_TEMPLATE = """
            Carefully read through the entire conversation history, analyze and determine whether
            we have completed the original requirements.

            Review your previous response and evaluate:
            1. Does your solution meet all requirements?
            2. Is your answer complete and accurate?
            3. Can you improve clarity or efficiency?

            If we need to continue reflecting, provide an improved solution based on your analysis.
            If satisfied with your answer, begin your response with 'TERMINATE' followed by the final solution.
            """;

    /**
     * Reflection template with evaluation criteria support.
     * Uses structured evaluation with business standards.
     *
     * This template is used in an independent LLM context (evaluator).
     * The evaluation feedback will be passed to the agent for regeneration.
     *
     * This template expects to be used with String formatting to inject:
     * 1. The original task/query
     * 2. The evaluation criteria (business standards)
     * 3. The current solution to evaluate
     */
    public static final String DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE = """
            You are an expert evaluator. Please review the solution based on the provided criteria.

            Original Task:
            {{task}}

            Evaluation Criteria (Business Standards):
            {{evaluationCriteria}}

            Current Solution:
            {{solution}}

            Please provide a detailed evaluation with the following:

            1. **Score (1-10)**: Rate how well the solution meets the evaluation criteria.

            2. **Strengths**: List the aspects of the solution that are well done.

            3. **Weaknesses**: Identify what needs improvement.

            4. **Specific Suggestions**: Provide concrete, actionable recommendations for improvement.

            If the score is 8 or higher and the solution meets all criteria, begin your response with 'TERMINATE'.

            Focus on providing clear, actionable feedback that will help improve the solution in the next iteration.
            """;

    /**
     * Simplified reflection template for when no evaluation criteria are provided.
     * Uses general quality assessment.
     */
    public static final String SIMPLE_REFLECTION_TEMPLATE = """
            Review the conversation and your previous response.

            Consider:
            - Is the solution complete and correct?
            - Are there any edge cases not addressed?
            - Can the solution be improved or optimized?

            If improvements are needed, provide an enhanced solution.
            If satisfied with the current solution, begin with 'TERMINATE' followed by the final answer.
            """;
}
