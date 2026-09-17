package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.prompt.PromptInject;
import ai.core.server.domain.AgentDefinition;

/**
 * Tells a personal assistant how to honour an explicit remember request. Attached together with the
 * extract_memory_now tool, so the instruction and the tool never appear apart.
 *
 * <p>Like {@link ai.core.server.agent.UserIdentityPrompt} the section lives on
 * {@link ExecutionContext#getPromptSections()}, and it is only attached for a template fork with memory
 * enabled: the fork's agent id is per user, so a memory written here can never reach another user.
 *
 * @author Xander
 */
public final class MemoryWritePrompt implements PromptInject {
    private static final String TITLE = "## Long-Term Memory";
    private static final String INSTRUCTION = """

            The user sometimes asks you to keep something for later sessions ("记住…", "remember…",
            "don't forget…"). When that happens, call extract_memory_now before answering or calling any
            other tool, and pass the fact as one self-contained statement in `focus`.

            Store preferences, stable facts and pitfalls. Do not store one-off task details, anything
            already covered by your instructions, or secrets the user did not ask you to keep.

            Do not call the tool when the user merely asks a question, gives an instruction that only
            applies to the current conversation, or asks you to recall something: recalling is answering.
            What you store shows up in later sessions, never in the current one.
            """;

    /** Attaches the remember instructions when the executing agent is a personal assistant with memory enabled. */
    public static void attach(ExecutionContext context, AgentDefinition definition) {
        if (context == null || context.getPromptSections() == null) return;
        if (!AgentMemoryService.rememberEnabled(definition)) return;
        context.getPromptSections().add(new MemoryWritePrompt());
    }

    private final String content;

    private MemoryWritePrompt() {
        this.content = TITLE + INSTRUCTION;
    }

    @Override
    public String inject() {
        return content;
    }

    @Override
    public SectionType type() {
        return SectionType.MEMORY;
    }
}
