package ai.core.cli.listener;

import ai.core.api.server.session.AgentSession;
import ai.core.cli.ui.TerminalUI;

/**
 * @author stephen
 */
public class RemoteEventListener extends BaseEventListener {
    public RemoteEventListener(TerminalUI ui, AgentSession session) {
        super(ui, session);
    }
}
