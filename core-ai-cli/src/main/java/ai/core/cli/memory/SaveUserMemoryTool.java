package ai.core.cli.memory;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

/**
 * Appends a durable cross-project preference to the global user memory
 * ({@code ~/.core-ai/MEMORY.md}), which is injected into the system prompt for every workspace.
 *
 * <p>Complements the workspace knowledge wiki: project facts stay in
 * {@code .core-ai/knowledge/}, only knowledge that must survive across projects belongs here.
 *
 * @author stephen
 */
public final class SaveUserMemoryTool extends ToolCall {

    public static final String TOOL_NAME = "save_user_memory";

    public static Builder builder() {
        return new Builder();
    }

    private final GlobalUserMemory globalMemory;

    private SaveUserMemoryTool(GlobalUserMemory globalMemory) {
        this.globalMemory = globalMemory;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = parseArguments(arguments);
            String content = getStringValue(argsMap, "content");
            if (content == null || content.isBlank()) {
                return ToolCallResult.failed("content is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            boolean created = globalMemory.append(content);
            return ToolCallResult.completed("Remembered for all workspaces: " + content.strip())
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("path", globalMemory.path().toAbsolutePath().toString())
                    .withStats("created", created);
        } catch (RuntimeException e) {
            return ToolCallResult.failed("Failed to save user memory: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    public static final class Builder extends ToolCall.Builder<Builder, SaveUserMemoryTool> {
        private GlobalUserMemory globalMemory = new GlobalUserMemory();

        public Builder globalMemory(GlobalUserMemory globalMemory) {
            this.globalMemory = globalMemory;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public SaveUserMemoryTool build() {
            this.name(TOOL_NAME);
            this.description("""
                    Append one durable preference to the GLOBAL user memory (~/.core-ai/MEMORY.md), which is
                    loaded for every workspace.
                    Use it only for knowledge that applies beyond the current project:
                    - Language, formatting or communication preferences ("always answer in Chinese")
                    - Workflow and tooling habits ("prefer ripgrep over grep", "use the local wrapper")
                    - Corrections that are not tied to this codebase
                    Do NOT use it for project facts (those go to the workspace knowledge wiki), for
                    session-local choices, or for anything transient.
                    Prefer the user's own wording; keep it to one concise sentence.""");
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "content",
                                    "The preference to remember, as one concise sentence.")
                            .required()));
            var tool = new SaveUserMemoryTool(globalMemory);
            build(tool);
            return tool;
        }
    }
}
