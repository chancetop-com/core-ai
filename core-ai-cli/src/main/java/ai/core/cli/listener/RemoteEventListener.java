package ai.core.cli.listener;

import ai.core.api.server.session.AgentSession;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.ThinkingSpinner;

/**
 * @author stephen
 */
public class RemoteEventListener extends BaseEventListener {
    public RemoteEventListener(TerminalUI ui, AgentSession session) {
        super(ui, session);
    }

    @Override
    protected void printTurnSummary() {
        long elapsed = spinner.getElapsedMs();
        String time = ThinkingSpinner.formatElapsed(elapsed);
        ui.getWriter().println("\n" + AnsiTheme.MUTED + "  \u2726 " + time + AnsiTheme.RESET);
        ui.getWriter().flush();
    }
}
