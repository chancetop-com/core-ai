package ai.core.cli.agent;

import ai.core.cli.DebugLog;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.BannerPrinter;
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
    private final boolean autoApproveAll;
    private final int maxTurn;

    public AgentSessionRunner(TerminalUI ui, LLMProviders providers, String modelOverride, boolean autoApproveAll, int maxTurn) {
        this.ui = ui;
        this.providers = providers;
        this.modelOverride = modelOverride;
        this.autoApproveAll = autoApproveAll;
        this.maxTurn = maxTurn;
    }

    public void run() {
        var sessionId = UUID.randomUUID().toString();
        var agent = CliAgent.of(providers, modelOverride, maxTurn);
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll);
        var listener = new CliEventListener(ui, session);
        session.onEvent(listener);

        var commands = new ReplCommandHandler(ui);

        String modelName = modelOverride != null ? modelOverride : providers.getProvider().config.getModel();
        BannerPrinter.print(ui.getWriter(), ui.getTerminalWidth(), modelName);

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
