package ai.core.prompt;

/**
 * @author stephen
 */
public class Prompts {
    public static final String IMAGE_CAPTIONING_PROMPT = """
            Provide a detailed and vivid description of the image, capturing its key elements, context, and mood.
            Focus on the most prominent features and any notable details that stand out.
            Use descriptive language to convey the scene effectively.
            Keep the caption concise.
            """;
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
    public static final String EMPTY_RESPONSE_REMINDER = "Your previous response was empty. Please provide a complete response.";
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
            You are analyzing a conversation between a user and an AI coding assistant.
            Extract knowledge that will make the assistant more effective in future sessions on this project.

            EXTRACT these types (use the exact type string):
            - "feedback": The user corrected the assistant, said "no"/"don't"/"stop doing X", or explicitly validated a non-obvious approach. \
            Format content as: "<The rule>. **Why:** <reason given>. **How to apply:** <when this kicks in>."
            - "user": The user's technical expertise, role, workflow preferences, or tool choices that affect how to collaborate.
            - "project": Non-obvious project facts, architectural decisions, constraints, or ongoing work context not derivable from code.
            - "reference": Locations of key files, external systems, docs, dashboards, or named resources the user pointed to.

            DO NOT extract:
            - Step-by-step execution history ("user ran X then Y")
            - Information already obvious from reading the code
            - One-off task details with no future relevance
            - Small talk or greetings

            For each memory:
            - "content": concise standalone statement (feedback type: follow the rule/Why/How-to-apply structure)
            - "type": one of "user", "feedback", "project", "reference"
            - "topic": broad 2–4 word kebab-case label (e.g. "memory-management", "self-improvement", "coding-conventions"). \
            Semantically related knowledge MUST share the same topic — it will be merged into one file. \
            Avoid fine-grained per-fact topics; prefer broader themes that group related rules together.
            - "importance": 0.0–1.0 (skip anything below 0.6)

            TOPIC GROUPING RULES (critical):
            - All feedback about memory indexing, aggregation, or description writing → topic: "memory-management"
            - All feedback about code style, naming, or comments → topic: "coding-conventions"
            - All project facts about the self-improvement system, learnings workflow, or session extraction → topic: "self-improvement"
            - When in doubt: fewer, broader topics are better than many narrow ones.

            Return [] if nothing is worth preserving.

            Conversation:
            %s

            Response format:
            [{"content": "...", "type": "feedback", "topic": "memory-management", "importance": 0.8}, ...]
            """;

    public static final String MEMORY_WRITER_PROMPT = """
            Write the following extracted memories into the project memory files.

            Memory directory: %s
            Memory index: %s

            Extracted memories (JSON):
            %s

            Rules (CRITICAL — read all before writing):
            - The existing memory files and their content are already loaded in your system prompt context.
            - Prefer merging into an existing file over creating a new one. \
            Check context first: if any existing file covers the same semantic area, append there.
            - Only create a new file when no existing file covers the same topic area.
            - Keep the total number of memory files small; high knowledge density per file is the goal.

            Step 1 — consolidate within this batch before any file I/O:
            - Group all entries by their target file ({type}-{topic}.md).
            - Multiple entries sharing the same topic must be written to the same file in one operation.
            - Entries with different topics but the same semantic area should also be merged into one file.

            Step 2 — for each consolidated group:
            1. Check existing memories in your context to find a semantically matching file (same type and related subject)
            2. If match found: append all group bullets to that file (skip duplicates), update Updated in index
            3. If no match: create {memory_dir}/{type}-{topic}.md with frontmatter:
               ---
               name: {topic}
               description: {type} memories — {topic}
               type: {type}
               ---
               Then append all group bullets.
            4. Update MEMORY.md index (create if missing):
               # Memory Index

               | File | Description | Created | Updated |
               |------|-------------|---------|---------|

               New file → add row with current datetime; existing file → update Updated only.
               Description: use the `description` field from the file's YAML frontmatter.

            IMPORTANT: Use the current datetime "%s" for all date fields (Created/Updated columns).
            """;

    public static final String LEARNINGS_PROMOTER_PROMPT = """
            Use the self-improvement skill to promote pending high-priority learning entries to permanent memory.

            Learning files directory: %s
            Memory directory: %s
            Memory index: %s

            Steps:
            1. Call use_skill("self-improvement") to load the skill instructions
            2. Follow the skill's promotion guidelines to identify and promote worthy entries
            3. Remove promoted sections from the source learning files

            IMPORTANT: Use the current datetime "%s" for all date fields (Created/Updated columns).
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
