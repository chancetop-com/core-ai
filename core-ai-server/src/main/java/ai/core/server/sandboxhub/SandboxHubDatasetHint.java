package ai.core.server.sandboxhub;

/**
 * Turns "this session has no dataset bindings" into an actionable sentence. Dataset bindings are captured when a
 * session is created, so an agent edited mid-conversation keeps its old tool set; without this the client only
 * sees an empty list and the user has to guess whether the problem is permissions, the schema or the session.
 *
 * @author stephen
 */
final class SandboxHubDatasetHint {
    static String describe(boolean sessionHasBindings, int agentBindingCount) {
        if (sessionHasBindings) return null;
        if (agentBindingCount <= 0) {
            return "this session has no dataset bindings and its agent has none either; "
                    + "bind a dataset to the agent, then start a new session";
        }
        return "this session has no dataset bindings; its agent now has " + agentBindingCount
                + " dataset binding(s), but dataset bindings are captured when a session is created — "
                + "start a new session to use them";
    }

    private SandboxHubDatasetHint() {
    }
}
