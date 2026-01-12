package ai.core.prompt;

/**
 * @author stephen
 */
public class Prompts {
    public static final String ROLE_PLAY_SYSTEM_PROMPT_TEMPLATE = "You are {} in a role play game.";
    public static final String REALISM_PHOTO_STYLE_IMAGE_PROMPT_SUFFIX = "Realistic style, photorealistic, raw photo, sigma 85mm f/1.4.";
    public static final String CONFIRMATION_PROMPT = "yes";
    public static final String WRITE_TODOS_SYSTEM_PROMPT = """
            
            ## `write_todos`
            
            You have access to the `write_todos` tool to help you manage and plan complex objectives.
            Use this tool for complex objectives to ensure that you are tracking each necessary step and giving the user visibility into your progress.
            This tool is very helpful for planning complex objectives, and for breaking down these larger complex objectives into smaller steps.
            
            It is critical that you mark todos as completed as soon as you are done with a step. Do not batch up multiple steps before marking them as completed.
            For simple objectives that only require a few steps, it is better to just complete the objective directly and NOT use this tool.
            Writing todos takes time and tokens, use it when it is helpful for managing complex many-step problems! But not for simple few-step requests.
            
            ## Important To-Do List Usage Notes to Remember
            - The `write_todos` tool should never be called multiple times in parallel.
            - Don't be afraid to revise the To-Do list as you go. New information may reveal new tasks that need to be done, or old tasks that are irrelevant.
            """;
    public static final String TOOL_DIRECT_RETURN_REMINDER_PROMPT = """
              %s successfully executed.
              <system-reminder>
              This tool is triggered manually by the user or executed automatically by the system.
              please return the tool results directly to the user.
              The tool result is :

              %s

              </system-reminder>
            """;
    public static final String COMPRESSION_SUMMARY_PREFIX = "[Previous Conversation Summary]\n";
    public static final String COMPRESSION_SUMMARY_SUFFIX = "\n[End Summary]";
    public static final String COMPRESSION_PROMPT = """
            Summarize the following conversation into a concise summary.
            Requirements:
            1. Preserve key facts, decisions, and context
            2. Keep important user preferences and goals mentioned
            3. Remove redundant back-and-forth and filler content
            4. Use bullet points for clarity
            5. Keep within %d words

            Conversation to summarize:
            %s

            Output summary directly:
            """;
    public static final String MEMORY_EXTRACTION_PROMPT = """
            Analyze the following conversation and extract memorable information about the user.

            Conversation:
            %s

            Return a JSON array of extracted memories. Each memory should have:
            - "content": the extracted information as a clear, standalone statement
            - "importance": a number from 0.0 to 1.0 indicating how important this information is for future interactions

            Guidelines for importance:
            - 0.9-1.0: Critical personal info (name, core preferences, important goals)
            - 0.7-0.8: Useful context (occupation, interests, ongoing projects)
            - 0.5-0.6: Nice to know (casual mentions, minor preferences)
            - Below 0.5: Skip - not worth storing

            Only extract meaningful, non-trivial information. Skip greetings and small talk.
            If no meaningful information can be extracted, return an empty array: []

            Response format:
            [{"content": "...", "importance": 0.8}, ...]
            """;

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
              "suggestions": ["actionable recommendations for improvement"]
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

            **Important:**0
            - Provide your evaluation as valid JSON
            - Be specific and actionable in your feedback
            - Focus on helping improve the solution iteratively
            """;

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
