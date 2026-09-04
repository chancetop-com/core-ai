package ai.core.cli.memory;

import ai.core.prompt.PromptInject;

/**
 * System prompt section injected into the main agent when memory is enabled.
 * Carries only the current knowledge block (&lt;memories&gt;).
 * Extraction procedures, file formats, and knowledge type rules are retrieved
 * on demand via the {@link MemoryExtractionTool}.
 */
public final class MemorySystemPrompt implements PromptInject {

    private static final String MEMORY_PROMPT_TEMPLATE = "%n## Existing Knowledge%n%n<memories>%n%s%n</memories>%n%n"
            + "The content above inside <memories> is the current knowledge wiki — treat it as the project's%n"
            + "collective memory. Use it to quickly orient yourself and locate relevant files before starting a task.%n"
            + "However, project knowledge may be stale (files move, architecture evolves) — always verify%n"
            + "by reading actual project files before making decisions. The code itself is the source of truth.%n%n"
            + "Exception: if you discover that a knowledge wiki page records a fact that is no longer%n"
            + "accurate — not due to changes made in the current session — correct the affected page and%n"
            + "MEMORY.md immediately. Call `get_memory_extraction_spec` if you need the full file format%n"
            + "and editing rules.%n%n"
            + "## Explicit Memory Requests (remember / 记住)%n"
            + "When the user asks you to remember something across sessions (\"记住…\", \"请记住…\", \"别忘了…\",%n"
            + "\"remember…\", \"don't forget…\", \"keep in mind…\", \"note that…\"), a text acknowledgment is NOT%n"
            + "remembering — you MUST persist it into the memory system before doing anything else:%n"
            + "1. FIRST call `extract_memory_now` — before any other tool, before answering, and before%n"
            + "   executing any other part of the request. Pass `focus` = the fact/preference/rule to store.%n"
            + "2. WAIT for it to complete — it synchronously runs the memory sub-agent, which writes or%n"
            + "   updates the knowledge wiki page(s), refreshes the MEMORY.md index, and advances the%n"
            + "   extraction cursor.%n"
            + "3. THEN continue with the user's request (e.g. perform the operation they asked for).%n"
            + "When a single message mixes memory and work (\"记住…，然后…\"), memorize first, then do the work.%n"
            + "Do NOT call `extract_memory_now` for: recall questions (\"还记得…吗？\", \"do you remember…?\")%n"
            + "— answer from <memories> or ask the user; routine instructions meant only for the current%n"
            + "conversation; timed reminders (\"提醒我…\", \"remind me to…\") — those are scheduled tasks,%n"
            + "not memory.%n%n"
            + "When extracting knowledge from conversations, call `get_memory_extraction_spec` to get%n"
            + "the full memory architecture, file formats, knowledge types, and extraction procedure.%n";

    private final String knowledgeContent;

    public MemorySystemPrompt(String knowledgeContent) {
        this.knowledgeContent = knowledgeContent;
    }

    @Override
    public SectionType type() {
        return SectionType.MEMORY;
    }

    @Override
    public String inject() {
        return MEMORY_PROMPT_TEMPLATE.formatted(
                knowledgeContent.isBlank() ? "(empty)" : knowledgeContent);
    }
}
