package ai.core.task;

import ai.core.agent.AgentChain;

/**
 * @author stephen
 */
public class Task {
    public String id;
    public TaskStatus status;
    public String topic; // summary first query and completion
    public AgentChain agentChain;
}
