package ai.core.agent;

/**
 * Content-size proxy for one agent's conversation state. It is not a deep object-graph measurement:
 * message text, inline media, tool calls and reasoning are counted as character units plus a fixed
 * per-message and per-session overhead, so the numbers are comparable across sessions and pods.
 *
 * @author stephen
 */
public record AgentFootprint(
        int turnCount,
        int messageCount,
        int historyCount,
        long contextTokens,
        long textBytes,
        long inlineImageBytes,
        long fileBytes,
        long videoBytes,
        long reasoningBytes,
        long toolCallBytes,
        long toolResultBytes,
        long estimatedBytes
) {
    /** Bump when the content-size formula changes, so samples from different formulas are not mixed. */
    public static final int ESTIMATE_VERSION = 1;
    public static final AgentFootprint EMPTY = new AgentFootprint(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
