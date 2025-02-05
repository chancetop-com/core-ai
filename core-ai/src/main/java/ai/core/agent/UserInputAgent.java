package ai.core.agent;

import ai.core.llm.providers.inner.Message;

import java.util.Map;

/**
 * @author stephen
 */
public class UserInputAgent extends Node<UserInputAgent> {

    public static Builder builder() {
        return new Builder();
    }

    @Override
    String execute(String query, Map<String, Object> variables) {
        if (this.getNodeStatus() == NodeStatus.INITED) {
            this.setInput(query);
            addMessage(Message.of(AgentRole.ASSISTANT, this.getName(), query));
            this.updateNodeStatus(NodeStatus.WAITING_FOR_USER_INPUT);
        } else if (this.getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT) {
            this.setOutput(query);
            addMessage(Message.of(AgentRole.USER, this.getName(), query));
            this.updateNodeStatus(NodeStatus.COMPLETED);
        }
        return query;
    }

    public static class Builder extends Node.Builder<Builder, UserInputAgent> {
        @Override
        protected Builder self() {
            return this;
        }

        public UserInputAgent build() {
            var agent = new UserInputAgent();
            this.nodeType = NodeType.USER_INPUT;
            this.name = "user-input-agent";
            this.description = "agent that accepts user input when other agent need user input and returns it";
            this.persistence = new UserInputAgentPersistence();
            build(agent);
            if (persistenceProvider == null) {
                throw new IllegalArgumentException("persistenceProvider is required");
            }
            return agent;
        }
    }
}
