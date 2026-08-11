package ai.core.agent;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.persistence.Persistence;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public class AgentPersistence implements Persistence<Agent> {
    public static String firstUserMessage(String data) {
        if (data == null || data.isBlank()) return null;
        var domain = JSON.fromJSON(AgentPersistenceDomain.class, data);
        if (domain.history != null) {
            for (var message : domain.history) {
                if (message.role == RoleType.USER) return message.getTextContent();
            }
        }
        if (domain.messages == null) return null;
        for (var message : domain.messages) {
            if (message.role == RoleType.USER) return message.getTextContent();
        }
        return null;
    }

    @Override
    public String serialization(Agent agent) {
        return JSON.toJSON(AgentPersistenceDomain.of(agent.getMessages(), agent.getHistory(), agent.getNodeStatus()));
    }

    @Override
    public void deserialization(Agent agent, String context) {
        if (context == null || context.isBlank()) return;
        var domain = JSON.fromJSON(AgentPersistenceDomain.class, context);
        agent.restorePersistedState(domain.messages, domain.history);
        agent.setNodeStatus(domain.status);
    }

    public static class AgentPersistenceDomain {

        public static AgentPersistenceDomain of(List<Message> messages, List<Message> history, NodeStatus status) {
            var domain = new AgentPersistenceDomain();
            domain.messages = messages;
            domain.history = history;
            domain.status = status;
            return domain;
        }

        @Property(name = "messages")
        public List<Message> messages;

        @Property(name = "history")
        public List<Message> history;

        @Property(name = "status")
        public NodeStatus status;
    }
}
