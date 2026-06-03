package ai.core.cli.memory;

import ai.core.prompt.PromptInject;

/**
 * System prompt section injected into the main agent when memory is enabled.
 * Carries only the current knowledge block (&lt;memories&gt;).
 * Extraction procedures, file formats, and knowledge type rules are retrieved
 * on demand via the {@link MemoryExtractionTool}.
 */
public final class MemorySystemPrompt implements PromptInject {

    private static final String MEMORY_PROMPT_TEMPLATE = """
            
            ## Existing Knowledge
            
            <memories>
            %s
            </memories>

            The content above inside <memories> is the current knowledge wiki — treat it as the project's
            collective memory. Use it to quickly orient yourself and locate relevant files before starting a task.
            However, project knowledge may be stale (files move, architecture evolves) — always verify
            by reading actual project files before making decisions. The code itself is the source of truth.
            
            Exception: if you discover that a knowledge wiki page records a fact that is no longer
            accurate — not due to changes made in the current session — correct the affected page and
            MEMORY.md immediately. Call `get_memory_extraction_spec` if you need the full file format
            and editing rules.
            
            When extracting knowledge from conversations, call `get_memory_extraction_spec` to get
            the full memory architecture, file formats, knowledge types, and extraction procedure.
            """;

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
