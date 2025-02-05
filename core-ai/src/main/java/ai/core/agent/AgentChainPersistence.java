package ai.core.agent;

import ai.core.llm.providers.inner.Message;
import ai.core.persistence.Persistence;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public class AgentChainPersistence implements Persistence<AgentChain> {
    @Override
    public String serialization(AgentChain agentChain) {
        return JSON.toJSON(AgentChainPersistenceDomain.of(agentChain.getMessages()));
    }

    @Override
    public void deserialization(AgentChain agentChain, String t) {
        var domain = JSON.fromJSON(AgentGroupPersistence.AgentGroupPersistenceDomain.class, t);
        agentChain.addMessages(domain.messages);
    }

    public static class AgentChainPersistenceDomain {
        public static AgentChainPersistenceDomain of(List<Message> messages) {
            var domain = new AgentChainPersistenceDomain();
            domain.messages = messages;
            return domain;
        }

        @Property(name = "messages")
        public List<Message> messages;
    }
}
