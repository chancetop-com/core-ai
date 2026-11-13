package ai.core.agent.subagent;

/**
 * The model a subagent thread runs on. Stored in the thread index beside the conversation, so a
 * restored thread keeps the model it was created with instead of following whatever the current
 * configuration happens to be.
 *
 * @author stephen
 */
public record SubagentModelRef(String model, String provider) {
}
