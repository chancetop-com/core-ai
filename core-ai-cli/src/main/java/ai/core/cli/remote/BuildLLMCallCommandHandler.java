package ai.core.cli.remote;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

/**
 * @author stephen
 */
public class BuildLLMCallCommandHandler {
    private static final String BUILDER_AGENT_ID = "llm-call-builder";

    private final TerminalUI ui;
    private final RemoteApiClient api;

    public BuildLLMCallCommandHandler(TerminalUI ui, RemoteApiClient api) {
        this.ui = ui;
        this.api = api;
    }

    public void handle() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  LLM Call Builder" + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Describe the LLM Call API you want to build." + AnsiTheme.RESET + "\n");
        new AgentChatSession(ui, api).start(BUILDER_AGENT_ID, "builder> ");
    }
}
