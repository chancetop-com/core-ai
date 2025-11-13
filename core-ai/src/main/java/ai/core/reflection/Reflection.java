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
     * This template is used as the SYSTEM prompt in an independent LLM context (evaluator).
     * The solution to evaluate will be provided as a USER message separately.
     * The evaluation feedback will be passed to the agent for regeneration.
     *
     * This template expects Mustache variables:
     * - {{task}}: The original task/query
     * - {{evaluationCriteria}}: Business standards and requirements
     */
    public static final String DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE = """
            You are an expert evaluator. Your role is to assess solutions based on the provided criteria and give constructive feedback.

            **Original Task:**
            {{task}}

            **Evaluation Criteria (Business Standards):**
            {{evaluationCriteria}}

            **Your Evaluation Guidelines:**

            When you receive a solution to evaluate, please provide a detailed assessment in JSON format:

            ```json
            {
              "score": <integer 1-10>,
              "pass": <boolean, whether it meets all critical requirements>,
              "should_continue": <boolean, whether improvement is recommended>,
              "confidence": <float 0.0-1.0, how confident you are in this evaluation>,
              "strengths": ["list of specific strengths"],
              "weaknesses": ["list of specific issues"],
              "suggestions": ["actionable recommendations for improvement"],
              "dimensions": {
                "correctness": <1-10>,
                "completeness": <1-10>,
                "quality": <1-10>
              }
            }
            ```

            **Scoring Guidelines:**
            - **9-10**: Excellent, meets all requirements perfectly
            - **7-8**: Good, meets most requirements with minor issues
            - **5-6**: Adequate, but has significant gaps
            - **3-4**: Poor, missing major requirements
            - **1-2**: Inadequate, needs complete rework

            **Termination Logic:**
            - Set `should_continue: false` if score >= 8 and all critical requirements are met
            - Set `should_continue: true` if improvements are still needed

            **Important:**
            - Provide your evaluation as valid JSON
            - Be specific and actionable in your feedback
            - Focus on helping improve the solution iteratively
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
