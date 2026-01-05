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
    public static final String SHORT_TERM_MEMORY_SUMMARY_PREFIX = "[Previous Conversation Summary]\n";
    public static final String SHORT_TERM_MEMORY_SUMMARY_SUFFIX = "\n[End Summary]";
    public static final String SHORT_TERM_MEMORY_COMPRESS_PROMPT = """
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
    public static final String LONG_TERM_MEMORY_EXTRACTION_PROMPT = """
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
}
