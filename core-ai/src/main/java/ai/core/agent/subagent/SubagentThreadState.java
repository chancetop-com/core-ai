package ai.core.agent.subagent;

/**
 * Runtime state of a subagent thread: LIVE has the agent in memory and can be continued right away,
 * DORMANT only exists as persisted data and is rebuilt on the next continuation, RUNNING is executing
 * a turn and refuses further continuations until it finishes.
 *
 * @author stephen
 */
public enum SubagentThreadState {
    LIVE,
    DORMANT,
    RUNNING
}
