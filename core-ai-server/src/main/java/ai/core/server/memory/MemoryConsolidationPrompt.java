package ai.core.server.memory;

/**
 * V2 memory extraction prompt — extract-only, no REFINE/MERGE.
 *
 * @author stephen
 */
final class MemoryConsolidationPrompt {
    static final String EXTRACTION_PROMPT = """
            You analyze an agent's execution traces and produce two things:

            1. A concise session trajectory summary (≤%d chars each)
            2. New reusable patterns observed in these traces

            ## Execution traces
            %s

            ## Task

            ### Part 1: Trajectory summaries
            For each session, write a short summary of what happened — the user request,
            key decisions made, tools used, and final outcome. Keep each summary under %d characters.
            Do NOT generalize or abstract — record what actually happened, faithfully.

            ### Part 2: Reusable patterns
            Extract new patterns that the agent can reuse in future runs.
            Only extract truly general patterns, not one-off facts about specific inputs.
            Classify each into one of: WORKFLOW_PATTERN | TOOL_USAGE | EFFICIENCY

            Rules:
            - DO NOT modify or merge with any previous memories — only extract what's NEW in these traces
            - Skip patterns already obvious from the SOP or system prompt
            - Keep each pattern under 200 words
            - Be specific and actionable

            Output ONLY valid JSON (no markdown fences, no extra text):
            {
              "trajectories": [
                { "session_id": "...", "summary": "..." }
              ],
              "patterns": [
                { "type": "EFFICIENCY", "content": "..." },
                { "type": "TOOL_USAGE", "content": "..." }
              ]
            }
            """;

    private MemoryConsolidationPrompt() {
    }
}
