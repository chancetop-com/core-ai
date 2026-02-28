package ai.core.cli.agent;

import ai.core.cli.DebugLog;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.TerminalUI;
import ai.core.llm.LLMProviders;
import ai.core.session.InProcessAgentSession;

import java.util.UUID;

/**
 * @author stephen
 */
public class AgentSessionRunner {
    private final TerminalUI ui;
    private final LLMProviders providers;
    private final String modelOverride;

    public AgentSessionRunner(TerminalUI ui, LLMProviders providers, String modelOverride) {
        this.ui = ui;
        this.providers = providers;
        this.modelOverride = modelOverride;
    }

    public void run() {
        var sessionId = UUID.randomUUID().toString();
        var agent = CliAgent.of(providers, modelOverride);
        var session = new InProcessAgentSession(sessionId, agent, false);
        var listener = new CliEventListener(ui, session);
        session.onEvent(listener);

        var commands = new ReplCommandHandler(ui);

        ui.printStreamingChunk("Welcome to Core-AI CLI\n");
        ui.printStreamingChunk("Session ID: " + sessionId + "\n");
        ui.printStreamingChunk("Type '/help' for commands, '/exit' to quit.\n");

        while (true) {
            var input = ui.readInput();
            if (input == null || "/exit".equalsIgnoreCase(input.trim())) {
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            if (input.trim().startsWith("/")) {
                commands.handle(input);
                continue;
            }

            DebugLog.log("sending message: " + input);
            listener.prepareTurn();
            session.sendMessage(input);
            DebugLog.log("waiting for turn...");
            listener.waitForTurn();
            DebugLog.log("turn finished");
        }

        session.close();
        ui.printStreamingChunk("Goodbye!\n");
    }
}
