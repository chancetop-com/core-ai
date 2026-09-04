package ai.core.cli.memory;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

/**
 * Main-agent tool that persists knowledge from the recent conversation (including the current
 * user message) into the knowledge wiki right now, and waits until the write completes.
 *
 * <p>The system prompt instructs the main agent to call this tool FIRST whenever the user
 * explicitly asks it to remember something (remember/记住), so memory is saved before the
 * agent continues with the rest of the request.
 */
public final class ExtractMemoryNowTool extends ToolCall {

    public static final String TOOL_NAME = "extract_memory_now";

    public static Builder builder() {
        return new Builder();
    }

    private final MemoryTriggerService service;

    public ExtractMemoryNowTool(MemoryTriggerService service) {
        this.service = service;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        String focus = null;
        try {
            focus = getStringValue(parseArguments(arguments), "focus");
        } catch (RuntimeException ignored) {
            // malformed arguments — run the extraction without a focus hint
        }
        service.runExplicitMemoryExtraction(focus);
        int cursor = service.extractionCursor.get();
        String message = cursor >= 0
                ? "Memory persisted: the extraction agent processed all unprocessed conversation through message "
                        + cursor + " and updated the knowledge wiki (.core-ai/knowledge/), MEMORY.md and log.md."
                : "Memory extraction completed: the extraction agent ran and updated the knowledge wiki (.core-ai/knowledge/).";
        return ToolCallResult.completed(message)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("cursor", cursor);
    }

    public static final class Builder extends ToolCall.Builder<Builder, ExtractMemoryNowTool> {
        private MemoryTriggerService service;

        public Builder service(MemoryTriggerService service) {
            this.service = service;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ExtractMemoryNowTool build() {
            if (service == null) {
                throw new IllegalStateException("service is required");
            }
            this.name(TOOL_NAME);
            this.description("""
                    Persist knowledge from the recent conversation (including the current user message)
                    into the long-term knowledge wiki now, and wait until the write completes.
                    Call this tool FIRST — before any other tool and before answering — when the user
                    explicitly asks you to remember something ("记住…", "请记住…", "别忘了…", "remember…",
                    "don't forget…", "keep in mind…", "note that…"). A text-only acknowledgment is NOT remembering.
                    It runs the memory extraction sub-agent synchronously: the sub-agent classifies the
                    conversation into project/user/feedback/reference wiki pages under .core-ai/knowledge/,
                    merges into existing pages, updates the MEMORY.md index, and advances the extraction cursor.
                    Do NOT call it for recall questions ("do you remember…?"), routine instructions meant only
                    for the current conversation, or timed reminders ("提醒我…", "remind me to…") — reminders
                    are scheduled tasks, not memory.""");
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "focus",
                            "The fact, preference, or rule the user asked you to remember (paraphrase it). "
                                    + "Pass it so the extraction guarantees this content is stored even if its "
                                    + "durability filters would normally skip it.")
            ));
            var tool = new ExtractMemoryNowTool(service);
            build(tool);
            return tool;
        }
    }
}
