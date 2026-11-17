package ai.core.tool.wait;

import ai.core.api.server.session.AgentWaitEvent;

/**
 * Host hook for the non-terminal wait progress (UI display). It must never start a model call or
 * block on one; dispatch only.
 *
 * @author stephen
 */
@FunctionalInterface
public interface WaitEventListener {
    void onWait(AgentWaitEvent event);
}
