package ai.core.agent;

import ai.core.llm.domain.Message;
import ai.core.persistence.Persistence;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public class AgentPersistence implements Persistence<Agent> {
    @Override
    public String serialization(Agent agent) {
        return JSON.toJSON(AgentPersistenceDomain.of(agent.getMessages(), agent.getNodeStatus()));
    }

    @Override
    public void deserialization(Agent agent, String context) {
        var domain = JSON.fromJSON(AgentPersistenceDomain.class, context);
        agent.addMessages(domain.messages);
        agent.setNodeStatus(domain.status);
    }

    public static class AgentPersistenceDomain {

        public static AgentPersistenceDomain of(List<Message> messages, NodeStatus status) {
            var domain = new AgentPersistenceDomain();
            domain.messages = messages;
            domain.status = status;
            return domain;
        }

        @Property(name = "messages")
        public List<Message> messages;

        @Property(name = "status")
        public NodeStatus status;
    }
}
